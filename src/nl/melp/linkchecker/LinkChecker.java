package nl.melp.linkchecker;

import nl.melp.linkchecker.Fetcher.Result;
import nl.melp.redis.Redis;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LinkChecker {
	private static final Logger logger = LoggerFactory.getLogger(LinkChecker.class);
	private final BlockingDeque<CloseableHttpClient> clients;
	final Status status;
	private final RunConfig config;
	private final ExecutorService executor;
	private final ScheduledExecutorService loggerService;
	private final Fetcher fetcher;
	private int timeout = 30;
	private final Set<ExecutorService> executorServices;
	private final LogMonitor logMonitor;

	public LinkChecker(RunConfig config, Status status, Fetcher fetcher) {
		this.fetcher = fetcher;
		this.status = status;
		this.config = config;

		AtomicInteger counter = new AtomicInteger(0);
		this.executor = Executors.newFixedThreadPool(config.getNumThreads(), runnable -> {
			Thread t = new Thread(runnable);
			t.setName("http-client-" + counter.incrementAndGet());
			return t;
		});
		this.loggerService = Executors.newScheduledThreadPool(1, runnable -> {
			Thread t = new Thread(runnable);
			t.setDaemon(true);
			t.setName("log-monitor");
			return t;
		});

		this.clients = new LinkedBlockingDeque<>(config.getNumThreads());
		for (int i = 0; i < config.getNumThreads(); i++) {
			clients.offer(config.createHttpClient());
		}

		executorServices = new HashSet<>();
		logMonitor = new LogMonitor(logger, this.status);

		executorServices.add(executor);
		executorServices.add(loggerService);
	}

	public void run() throws InterruptedException {
		HashMap<Future<?>, Long> startedAt = new HashMap<>();
		loggerService.scheduleAtFixedRate(logMonitor::log, 0, 5, TimeUnit.SECONDS);

		int i = 0;
		AtomicInteger size = new AtomicInteger(status.urls.size());
		for (final URI url : status.urls) {
			CloseableHttpClient httpClient = clients.take();
			startedAt.put(executor.submit(
				() -> {
					try {
						// give the system some rest (if configured)
						if (this.config.getDelayMs() > 0) {
							Thread.sleep(this.config.getDelayMs());
						}
						logger.trace("OPENING " + url);
						status.add(fetcher.fetch(httpClient, url));
					} catch (IllegalArgumentException e) {
						logger.warn(String.format("Error opening url %s (%s: %s); referred to by (at least) %s", url, e.getClass().getCanonicalName(), e.getMessage(), new HashSet<>(status.reverseLinks.getOrDefault(url, null))), e);
						status.add(new Result(url, 0, null, null));
					} catch (InterruptedException e) {
						e.printStackTrace();
						Thread.currentThread().interrupt();
						status.add(new Result(url, 0, null, null));
					} finally {
						clients.offer(httpClient);
					}
				}
			), System.currentTimeMillis());

			i ++;
			if (i < size.get()) {
				if (i % config.getNumThreads() == 0) {
					Set<Future<?>> remove = new LinkedHashSet<>();
					startedAt.keySet().forEach((r) -> {
						if (r.isDone()) {
							remove.add(r);
						}
					});
					remove.forEach(startedAt::remove);
				}
			} else {
				size.set(status.urls.size());

				if (i == size.get()) {
					Set<Future<?>> remove = new LinkedHashSet<>();

					logger.debug("Queue drained, resolving futures");
					startedAt.keySet().forEach((r) -> {
						if (!r.isCancelled() && startedAt.get(r) - System.currentTimeMillis() >= timeout * 1000) {
							r.cancel(false);
						} else if (startedAt.get(r) - System.currentTimeMillis() >= timeout * 2 * 1000) {
							r.cancel(true);
						} else {
							if (!r.isCancelled()) {
								try {
									r.get();
								} catch (InterruptedException | ExecutionException e) {
									e.printStackTrace();
								}
								remove.add(r);
							}
						}
					});
					remove.forEach(startedAt::remove);
				}
			}
		}

		executorServices.forEach(ExecutorService::shutdown);
	}

	public static void main(String[] rawArgs) throws InterruptedException, IOException, KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
		final RunConfig config = new RunConfig(logger, rawArgs);

		try (Redis.Managed redis = config.connect()) {
			Status status = config.createStatus(redis);
			LinkChecker linkChecker = new LinkChecker(config, status, new Fetcher(logger, config, new HtmlExtractor(logger), new URIResolver(logger)));
			linkChecker.run();
			config.report(redis, linkChecker);
		} catch (ConnectException e) {
			throw new RuntimeException(String.format("Error connecting to redis at %s:%s", config.getRedisHost(), config.getRedisPort()), e);
		}

		if (!config.hasFlag("resume") && !config.hasFlag("reset") && !config.hasFlag("report")) {
			System.err.println("None of --resume, --reset or --report given, no action taken.");
		}
	}

	public static boolean isErrorStatus(int v) {
		return v <= 0 || v >= 400;
	}
}

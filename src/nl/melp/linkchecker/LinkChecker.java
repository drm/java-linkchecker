package nl.melp.linkchecker;

import nl.melp.redis.Redis;
import nl.melp.redis.collections.SerializedHashMap;
import nl.melp.redis.collections.SerializedSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;

public class LinkChecker {
	private static Logger logger = LoggerFactory.getLogger(LinkChecker.class);
	private static int timeout = 30;

	private final BlockingDeque<HttpClient> clients;
	private final Map<URI, Integer> statuses;
	private final Collection<URI> urls;
	private final Map<URI, Set<URI>> reverseLinks;
	private final Map<String, Set<URI>> invalidUrls;
	private final ExecutorService executor;
	private final List<Future> running;
	private final BiPredicate<URI, URI> shouldFollowLinks;
	private final BiPredicate<URI, HttpResponse<String>> shouldExtractLinks;
	private long startTimeMs;

	public LinkChecker(
		Set<URI> urlsToCheck,
		Map<URI, Integer> results,
		Map<URI, Set<URI>> reverseLinks,
		Map<String, Set<URI>> invalidUrls,
		BiPredicate<URI, URI> shouldFollowLinks,
		BiPredicate<URI, HttpResponse<String>> shouldExtractLinks,
		int numThreads
	) {
		this.urls = urlsToCheck;
		this.shouldFollowLinks = shouldFollowLinks;
		this.shouldExtractLinks = shouldExtractLinks;
		this.statuses = results;
		this.reverseLinks = reverseLinks;
		this.invalidUrls = invalidUrls;
		this.executor = Executors.newFixedThreadPool(numThreads);
		this.running = new LinkedList<>();
		this.clients = new LinkedBlockingDeque<>(numThreads);

		for (int i = 0; i < numThreads; i++) {
			clients.offer(HttpClient.newHttpClient());
		}
	}

	public Map<URI, Set<URI>> getReverseLinks() {
		return reverseLinks;
	}

	public Map<String, Set<URI>> getInvalidUrls() {
		return invalidUrls;
	}

	public Map<URI, Integer> getStatuses() {
		return statuses;
	}

	private void logMonitor() {
		try {
			long dt = (System.currentTimeMillis() - startTimeMs) / 1000;
			int size = statuses.size();
			Set<Future> remove = new HashSet<>();
			synchronized (running) {
				for (var r : running) {
					if (r.isDone()) {
						remove.add(r);
					}
				}
				running.removeAll(remove);
			}
			Runtime rt = Runtime.getRuntime();
			long memTotal = rt.totalMemory();
			long memFree = rt.freeMemory();
			long memUsed = memTotal - memFree;
			float memUsagePct = (float) memUsed / (float) (memTotal / 100);
			if (memUsagePct > 95) {
				logger.warn("Memory consumption is high. This might cause instability. Consider increasing available memory with -Xmx and -Xms flags");
			}
			logger.info(
				String.format(
					"processed: %d, processing: %d, to check: %d; (run time %ds, avg %d/s, mem usage: %d MB of %d MB (%.2f%%))",
					size,
					running.size(),
					urls.size(),
					dt,
					size / (dt > 0 ? dt : 1),
					memUsed / 1024 / 1024,
					memTotal / 1024 / 1024,
					memUsagePct
				)
			);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public Map<URI, Integer> run() throws InterruptedException {
		Set<ExecutorService> executorServices = new HashSet<>();
		executorServices.add(executor);

		startTimeMs = System.currentTimeMillis();
		ScheduledExecutorService loggerService = Executors.newScheduledThreadPool(1);
		executorServices.add(loggerService);

		loggerService.scheduleAtFixedRate(this::logMonitor, 1, 1, TimeUnit.SECONDS);

		while (urls.size() > 0 || running.size() > 0) {
			URI url;
			synchronized (urls) {
				if (urls.size() > 0) {
					url = urls.stream().findFirst().orElseThrow();
					urls.remove(url);
				} else {
					continue;
				}
			}
			if (statuses.containsKey(url)) {
				continue;
			}

			statuses.put(url, -1);
			HttpClient l = clients.take();

			Future<?> task = executor.submit(
				() -> {
					try {
						if (statuses.get(url) >= 0) {
							return; // URL was resolved by another thread.
						}

						logger.trace("OPENING " + url);

						HttpRequest request = HttpRequest.newBuilder()
							.uri(url)
							.timeout(Duration.ofSeconds(timeout))
							.build();
						HttpResponse<String> response = l.send(request, HttpResponse.BodyHandlers.ofString());
						int status = response.statusCode();
						statuses.put(url, status);

						if (status >= 400) {
							logger.info("Got status " + status + " at " + url + "; so far referred to by " + reverseLinks.get(url));
						} else {
							logger.trace("Got status " + status + " at " + url);
						}

						if (shouldExtractLinks.test(url, response)) {
							if (status == 200) {
								if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/html")) {
									Document d = Jsoup.parse(response.body());
									Elements links = d.select("a[href]");
									logger.trace("Found " + links.size() + " on " + url);
									for (Element link : links) {
										addUrl(url, link.attr("href"));
									}
								} else {
									logger.trace("Not following links in content type " + response.headers().firstValue("Content-Type").orElse("UNKNOWN"));
								}
							} else if (response.headers().firstValue("Location").isPresent()) {
								String location = response.headers().firstValue("Location").get();
								if (addUrl(url, location)) {
									logger.trace("Following redirect (" + status + ") [" + url + " => " + location);
								}
							}
						}
					} catch (IOException e) {
						e.printStackTrace();
						logger.error("Error opening url " + url + " (" + e.getClass().getCanonicalName() + ": " + e.getMessage() + "; so far referred to by " + reverseLinks.get(url));
						statuses.put(url, 0);
					} catch (InterruptedException e) {
						e.printStackTrace();
						Thread.currentThread().interrupt();
					} catch (NullPointerException npe) {
						npe.printStackTrace();
						throw npe;
					} finally {
						clients.offer(l);
					}
				}
			);
			synchronized (running) {
				running.add(task);
			}
			if (urls.size() == 0) {
				logger.trace("Queue drained, waiting for first job to finish; still " + running.size() + " processing");
				running.stream().findFirst().ifPresent(r -> {
					try {
						r.get();
					} catch (InterruptedException | ExecutionException e) {
						e.printStackTrace();
					}
					running.remove(r);
				});
				// givin' it a bit of time ....
				Thread.sleep(200);
			}
		}

		executorServices.forEach(ExecutorService::shutdown);

		return statuses;
	}

	private boolean addUrl(URI context, String linkedUrl) {
		if (invalidUrls.containsKey(linkedUrl)) {
			invalidUrls.get(linkedUrl).add(context);
			return false;
		}

		URI uri;

		try {
			uri = URI.create(linkedUrl);
		} catch (IllegalArgumentException e) {
			registerInvalidUrl(context, linkedUrl, e.getMessage());
			return false;
		}

		if (uri.getPath() == null && uri.getScheme() == null) {
			registerInvalidUrl(context, linkedUrl, "Ignoring uri without path: " + linkedUrl);
			return false;
		}

		if (uri.getScheme() != null && !uri.getScheme().equals("https") && !uri.getScheme().equals("http")) {
			return false;
		}

		if (uri.getHost() == null || uri.getScheme() == null) {
			uri = URI.create(context.getScheme() + "://" + context.getHost() + (context.getPort() > 0 ? ":" + context.getPort() : "") + linkedUrl);
		}

		if (uri.getHost() == null) {
			registerInvalidUrl(context, linkedUrl, "Could not extract host");
			return false;
		}

		if (uri.getPath() == null) {
			logger.trace("Not following non-path url: " + uri);
			return false;
		}

		if (!shouldFollowLinks.test(context, uri)) {
			logger.trace("Not following blacklisted url: " + uri);
			return false;
		}

		if (!statuses.containsKey(uri)) {
			synchronized (urls) {
				logger.trace("Found url: " + uri);
				urls.add(uri);

				// not synchronizing here, because already synchronized(urls)
				if (!reverseLinks.containsKey(uri)) {
					reverseLinks.put(uri, new HashSet<>());
				}
				reverseLinks.get(uri).add(context);
			}
			return true;
		}

		return false;
	}


	private void registerInvalidUrl(URI mentionedAt, String url, String reason) {
		synchronized (invalidUrls) {
			if (!invalidUrls.containsKey(url)) {
				invalidUrls.put(url, new HashSet<>());
			}
			invalidUrls.get(url).add(mentionedAt);
			logger.warn("Invalid url: " + url + " (" + reason + ")");
		}
	}

	public static void main(String[] rawArgs) throws InterruptedException, IOException {
		Map<String, Set<String>> opts = new HashMap<>();
		Set<String> flags = new HashSet<>();
		List<String> args = new LinkedList<>();

		// Crude argument parsing
		Arrays.asList(rawArgs).forEach((s) -> {
			if (s.startsWith("--")) {
				if (s.contains("=")) {
					String[] split = s.split("=");
					String optName = split[0].substring(2);
					String[] values = split[1].split(",");
					if (!opts.containsKey(optName)) {
						opts.put(optName, new HashSet<>());
					}
					opts.get(optName).addAll(Arrays.asList(values));
				} else {
					flags.add(s);
				}
			} else {
				args.add(s);
			}
		});

		Set<String> localHosts = new HashSet<>();

		for (String startUri : args) {
			localHosts.add(URI.create(startUri).getHost());
		}

		Socket socket = new Socket(System.getProperty("redis.host", "localhost"), Integer.valueOf(System.getProperty("redis.port", "6379")));
		Redis redis = new Redis(socket);
		Set<URI> urls = new SerializedSet<>(redis, LinkChecker.class.getCanonicalName() + ".urls");
		for (String arg : args) {
			urls.add(URI.create(arg));
		}

		LinkChecker linkChecker = new LinkChecker(
			urls,
			new SerializedHashMap<>(redis, LinkChecker.class.getCanonicalName() + ".statuses"),
			new HashMap<>(),
			new HashMap<>(),
			(context, url) -> {
				if (opts.containsKey("include")) {
					for (String s : opts.get("include")) {
						if (url.getPath().matches(s)) {
							logger.trace("URL " + url + " matches pattern " + s + "; including");
							return true;
						}
					}

					return false;
				}
				if (opts.containsKey("ignore")) {
					for (String s : opts.get("ignore")) {
						if (url.getPath().matches(s)) {
							logger.trace("URL " + url + " matches pattern " + s + "; ignoring");
							return false;
						}
					}
				}
				if (localHosts.contains(url.getHost())) {
					return true;
				}
				if (flags.contains("follow-from-local")) {
					return localHosts.contains(context.getHost());
				}
				return false;
			},
			(context, response) -> !flags.contains("no-follow") && localHosts.contains(context.getHost()),
			opts.containsKey("threads") ? Integer.valueOf(opts.get("threads").stream().findFirst().orElse("40")) : 40
		);

		try {
			linkChecker.run();
		} finally {
			for (Map.Entry<String, Set<URI>> entry : linkChecker.getInvalidUrls().entrySet()) {
				System.out.println("INVALID: " + entry.getKey() + " (referred by following urls:)");
				for (URI referredBy : linkChecker.getInvalidUrls().get(entry.getKey())) {
					System.out.println(" + " + referredBy);
				}
			}

			int numErr = 0;
			int numSuccess = 0;
			for (Map.Entry<URI, Integer> r : linkChecker.getStatuses().entrySet()) {
				if (r.getValue() >= 400 || r.getValue() <= 0) {
					System.out.println("[" + r.getValue() + "] at " + r.getKey() + " (referred by following urls:)");
					for (URI referredBy : linkChecker.getReverseLinks().get(r.getKey())) {
						System.out.println(" + " + referredBy);
					}
					numErr++;
				} else {
					numSuccess++;
				}
			}
			System.out.println(
				String.format(
					"Success: %d, Errors: %d, Invalids: %d",
					numSuccess,
					numErr,
					linkChecker.getInvalidUrls().size()
				)
			);

			System.out.println("Total number of resolved statuses: " + linkChecker.getStatuses().size());
		}
	}
}

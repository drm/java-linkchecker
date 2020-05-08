package nl.melp.linkchecker;

import nl.melp.redis.Redis;
import nl.melp.redis.collections.*;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

public class LinkChecker {
	private static Logger logger = LoggerFactory.getLogger(LinkChecker.class);
	private static int timeout = 30;

	private static RequestConfig requestConfig = RequestConfig.custom()
		.setConnectTimeout(timeout * 1000)
		.setConnectionRequestTimeout(timeout * 1000)
		.setSocketTimeout(timeout * 1000)
		.build();

	private final BlockingDeque<CloseableHttpClient> clients;
	private final Map<URI, Integer> statuses;
	private final Collection<URI> urls;
	private final Map<URI, Set<URI>> reverseLinks;
	private final Map<String, Set<URI>> invalidUrls;
	private final ExecutorService executor;
	private final Set<Future<?>> running;
	private final BiPredicate<URI, URI> shouldFollowLinks;
	private final BiPredicate<URI, HttpEntity> shouldExtractLinks;
	private final int msDelay;
	private long startTimeMs;

	public LinkChecker(
		Set<URI> urlsToCheck,
		Map<URI, Integer> results,
		Map<URI, Set<URI>> reverseLinks,
		Map<String, Set<URI>> invalidUrls,
		BiPredicate<URI, URI> shouldFollowLinks,
		BiPredicate<URI, HttpEntity> shouldExtractLinks,
		int numThreads,
		int msDelay,
		boolean ignoreSslErrors
	) throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
		this.urls = urlsToCheck;
		this.shouldFollowLinks = shouldFollowLinks;
		this.shouldExtractLinks = shouldExtractLinks;
		this.statuses = results;
		this.reverseLinks = reverseLinks;
		this.invalidUrls = invalidUrls;
		AtomicInteger counter = new AtomicInteger(0);
		this.executor = Executors.newFixedThreadPool(numThreads, runnable -> {
			Thread t = new Thread(runnable);
			t.setName("http-client-" + counter.incrementAndGet());
			return t;
		});
		this.running = new HashSet<>();
		this.clients = new LinkedBlockingDeque<>(numThreads);
		this.msDelay = msDelay;

		HttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(); // default
		if (ignoreSslErrors) {
			final SSLContext sslContext = new SSLContextBuilder()
				.loadTrustMaterial(null, (x509CertChain, authType) -> true)
				.build();

			connectionManager = new PoolingHttpClientConnectionManager(
					RegistryBuilder.<ConnectionSocketFactory>create()
						.register("http", PlainConnectionSocketFactory.INSTANCE)
						.register("https", new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE))
						.build()
			);
		}

		for (int i = 0; i < numThreads; i++) {
			final CloseableHttpClient client = HttpClients.createMinimal(connectionManager);
			clients.offer(client);
		}
	}

	private void logMonitor() {
		try {
			long dt = (System.currentTimeMillis() - startTimeMs) / 1000;
			int size = statuses.size();

			synchronized (running) {
				Set<Future<?>> remove = new HashSet<>();
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

		ScheduledExecutorService loggerService = Executors.newScheduledThreadPool(1, runnable -> {
			Thread t = new Thread(runnable);
			t.setDaemon(true);
			t.setName("log-monitor");
			return t;
		});

		executorServices.add(loggerService);

		startTimeMs = System.currentTimeMillis();

		logMonitor();
		loggerService.scheduleAtFixedRate(this::logMonitor, 1, 1, TimeUnit.SECONDS);
		HashMap<Future<?>, Long> startedAt = new HashMap<>();
		do {
			for (URI url : urls) {
				urls.remove(url);
				if (statuses.containsKey(url)) {
					continue;
				}
				statuses.put(url, -1);
				CloseableHttpClient httpClient = clients.take();

				Future<?> task = executor.submit(
					() -> {
						try {
							if (statuses.get(url) >= 0) {
								return; // URL was resolved by another thread.
							}
							// give the system some rest (if configured)
							if (msDelay > 0) {
								Thread.sleep(msDelay);
							}

							logger.trace("OPENING " + url);

							var request = new HttpGet(url);
							request.setConfig(requestConfig);
							try (CloseableHttpResponse response = httpClient.execute(request)) {
								int status = response.getStatusLine().getStatusCode();
								statuses.put(url, status);

								if (status >= 400) {
									logger.info("Got status " + status + " at " + url + "; so far referred to by " + Arrays.toString(new HashSet<>(reverseLinks.get(url)).toArray()));
								} else {
									logger.trace("Got status " + status + " at " + url);
								}
								HttpEntity responseEntity = response.getEntity();

								if (shouldExtractLinks.test(url, responseEntity)) {
									Header contentTypeHeader = response.getFirstHeader("Content-Type");
									String contentType = contentTypeHeader == null ? "UNKNOWN" : contentTypeHeader.getValue();

									if (status == 200) {
										if (contentType.startsWith("text/html")) {
											Document d = Jsoup.parse(responseEntity.getContent(), "UTF-8", url.toString());
											Elements links = d.select("a[href]");
											logger.trace("Found " + links.size() + " on " + url);
											for (Element link : links) {
												addUrl(url, link.attr("href"));
											}
										} else {
											logger.trace("Not following links in content type " + contentType);
										}
									} else if (response.getFirstHeader("Location") != null) {
										String location = response.getFirstHeader("Location").getValue();
										if (addUrl(url, location)) {
											logger.trace("Following redirect (" + status + ") [" + url + " => " + location);
										}
									} else {
										logger.debug("Skipping {}, content-type: {}", url, contentType);
									}
								}
							}
						} catch (java.lang.IllegalArgumentException | IOException e) {
							statuses.put(url, 0);
							logger.warn(String.format("Error opening url %s (%s: %s; so far referred to by %s", url, e.getClass().getCanonicalName(), e.getMessage(), new HashSet<>(reverseLinks.getOrDefault(url, null))), e);
						} catch (InterruptedException e) {
							e.printStackTrace();
							Thread.currentThread().interrupt();
						} finally {
							clients.offer(httpClient);
						}
					}
				);
				// TODO `startedAt` and `running` can be merged.
				synchronized (startedAt) {
					startedAt.put(task, System.currentTimeMillis());
				}
				synchronized (running) {
					running.add(task);
				}
			}
			if (!urls.iterator().hasNext()) {
				logger.trace("Queue drained, waiting for first job to finish; still " + running.size() + " processing");
				running.forEach((r) -> {
					if (!r.isCancelled() && startedAt.get(r) - System.currentTimeMillis() >= timeout * 1000) {
						r.cancel(false);
					} else if (startedAt.get(r) - System.currentTimeMillis() >= timeout * 2 * 1000) {
						r.cancel(true);
					}
				});
				running.stream().findFirst().ifPresent(r -> {
					try {
						r.get();
					} catch (InterruptedException | ExecutionException e) {
						e.printStackTrace();
					}
					running.remove(r);
					startedAt.remove(r);
				});
				// givin' it a bit of time ....
				Thread.sleep(200);
			}
		} while (urls.iterator().hasNext() || running.size() > 0);

		executorServices.forEach(ExecutorService::shutdown);

		return statuses;
	}


	public void report(boolean all) {
		for (Map.Entry<String, Set<URI>> entry : invalidUrls.entrySet()) {
			System.out.println("INVALID: " + entry.getKey() + " - referred by following urls");
			for (URI referredBy : invalidUrls.get(entry.getKey())) {
				System.out.println(" + " + referredBy);
			}
		}

		int numErr = 0;
		int numSuccess = 0;
		for (Map.Entry<URI, Integer> r : statuses.entrySet()) {
			if (r.getValue() >= 400 || r.getValue() <= 0) {
				System.out.println("[" + r.getValue() + "] at " + r.getKey() + " (referred by following urls:)");
				for (URI referredBy : reverseLinks.get(r.getKey())) {
					System.out.println(" + " + referredBy);
				}
				numErr++;
			} else {
				if (all) {
					System.out.println("[OK] at " + r.getKey());
				}
				numSuccess++;
			}
		}
		System.out.println(
			String.format(
				"Success: %d, Errors: %d, Invalids: %d",
				numSuccess,
				numErr,
				invalidUrls.size()
			)
		);

		System.out.println("Total number of resolved statuses: " + statuses.size());
	}

	private boolean addUrl(URI context, String linkedUrl) {
		URI uri;

		try {
			uri = context.resolve(linkedUrl);
		} catch (IllegalArgumentException e) {
			registerInvalidUrl(context, linkedUrl, e.getMessage());
			return false;
		}

		if (!"".equals(uri.getFragment()) && uri.getFragment() != null) {
			uri = URI.create(uri.toString().replace("#" + uri.getRawFragment(), ""));
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
				reverseLinks.get(uri).add(context);
			}
			return true;
		}

		return false;
	}

	private void registerInvalidUrl(URI mentionedAt, String url, String reason) {
		invalidUrls.get(url).add(mentionedAt);
	}

	public static void main(String[] rawArgs) throws InterruptedException, IOException, KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
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
					flags.add(s.substring(2));
				}
			} else {
				args.add(s);
			}
		});

		Set<String> localHosts = new HashSet<>();

		for (String startUri : args) {
			localHosts.add(URI.create(startUri).getHost());
		}

		String redisHost = opts.getOrDefault("redis-host", Collections.emptySet()).stream().findFirst().orElse(System.getProperty("redis.host", "localhost"));
		String redisPort = opts.getOrDefault("redis-port", Collections.emptySet()).stream().findFirst().orElse(System.getProperty("redis.port", "6379"));

		try (Socket socket = new Socket(redisHost, Integer.parseInt(redisPort))) {
			Redis redis = new Redis(socket);
			Set<URI> urls = new SerializedSet<>(redis, LinkChecker.class.getCanonicalName() + ".urls");
			SerializedHashMap<URI, Integer> results = new SerializedHashMap<>(redis, LinkChecker.class.getCanonicalName() + ".statuses");

			Map<URI, Set<URI>> reverseLinks = new SerializedMappedSet<>(Serializers.of(URI.class), Serializers.of(URI.class), redis, prefixKeyName(".reverseLinks"));
			Map<String, Set<URI>> invalidUrls = new SerializedMappedSet<>(Serializers.of(String.class), Serializers.of(URI.class), redis, LinkChecker.class.getCanonicalName() + ".invalidUrls");
			Map<String, String> timestamps = new SerializedHashMap<>(Serializers.of(String.class), Serializers.of(String.class), redis, LinkChecker.class.getCanonicalName() + ".timestamps");

			if (flags.contains("reset")) {
				urls.clear();
				results.clear();
				reverseLinks.clear();
				invalidUrls.clear();
				timestamps.clear();
			} else if (!flags.contains("no-recheck") && (flags.contains("resume") || flags.contains("recheck"))) {
				Set<URI> reset = new HashSet<>();
				logger.info("Scanning " + results.size() + " results for recheckable links");
				results.forEach((k, v) -> {
					if (isErrorStatus(v)) {
						if (flags.contains("recheck-only-errors") && v > 0) {
							logger.trace("recheck - skip status {} for {}", v, k);
							return;
						}
						if (!flags.contains("recheck") && v >= 0) {
							logger.trace("recheck - skip status {} for {}", v, k);
							return;
						}
						logger.debug("recheck - add status {} for {}", v, k);
						urls.add(k);
						reset.add(k);
					} else {
						logger.trace("recheck - no error {} for {}", v, k);
					}
				});
				if (reset.size() > 0) {
					logger.info("{} found, restoring the to the queue", reset.size());
					urls.addAll(reset);
					reset.forEach(results::remove);
				} else {
					logger.info("None found.");
				}
			}

			for (String arg : args) {
				URI uri = URI.create(arg);
				if (uri.getPath() == null || uri.getPath().equals("")) {
					uri = uri.resolve("/");
				}
				urls.add(uri);
			}

			LinkChecker linkChecker = new LinkChecker(
				urls,
				results,
				reverseLinks,
				invalidUrls,
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
							if (url.toString().matches(s)) {
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
				opts.containsKey("threads") ? Integer.parseInt(opts.get("threads").stream().findFirst().orElse("40")) : 40,
				opts.containsKey("delay-ms") ? Integer.parseInt(opts.get("delay-ms").stream().findFirst().orElse("20")) : 20,
				flags.contains("ignore-ssl-errors")
			);

			if (flags.contains("resume") || flags.contains("reset") || flags.contains("recheck")) {
				timestamps.remove("stop");
				if (flags.contains("reset")) {
					timestamps.put("start", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME));
					timestamps.remove("resume");
				} else {
					timestamps.put("resume", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME));
				}
				try {
					linkChecker.run();
				} finally {
					timestamps.put("stop", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME));
				}
			}
			if (flags.contains("report")) {
				ISerializer<String> stringSerializer = new ISerializer<>() {
					@Override
					public byte[] serialize(String uri) {
						return uri.getBytes();
					}

					@Override
					public String deserialize(byte[] bytes) {
						return new String(bytes);
					}
				};
				SerializedHashMap<String, Integer> report = new SerializedHashMap<>(
					stringSerializer,
					new ISerializer<>() {
						@Override
						public byte[] serialize(Integer v) {
							return v.toString().getBytes();
						}

						@Override
						public Integer deserialize(byte[] bytes) {
							return Integer.valueOf(new String(bytes));
						}
					},
					redis, LinkChecker.class.getCanonicalName() + ".report.statuses");

				SerializedHashMap<String, String> refers = new SerializedHashMap<>(
					stringSerializer,
					stringSerializer,
					redis, LinkChecker.class.getCanonicalName() + ".report.referers");

				logger.info("Building report for " + results.size() + " keys and " + invalidUrls.size() + " invalids");
				report.clear();
				refers.clear();

				results.forEach((k, v) -> {
					if (flags.contains("report-all") || isErrorStatus(v)) {
						report.put(k.toString(), v);
						refers.put(k.toString(), String.join(
							"\n",
							reverseLinks.get(k).stream().map(URI::toString).toArray(String[]::new))
						);
					}
				});
				invalidUrls.forEach((s, uri) -> {
					report.put(s, 0);
					refers.put(s, String.join(
						"\n",
						uri.stream().map(URI::toString).toArray(String[]::new))
					);
				});
				linkChecker.report(flags.contains("report-all"));
			}
		} catch (ConnectException e) {
			throw new RuntimeException(String.format("Error connecting to redis at %s:%s", redisHost, redisPort), e);
		}

		if (!flags.contains("resume") && !flags.contains("reset") && !flags.contains("report")) {
			System.err.println("None of --resume, --reset or --report given, no action taken.");
		}
	}

	private static String prefixKeyName(String s) {
		return LinkChecker.class.getCanonicalName() + s;
	}

	private static boolean isErrorStatus(int v) {
		return v <= 0 || v >= 400;
	}
}

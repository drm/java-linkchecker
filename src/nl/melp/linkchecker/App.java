package nl.melp.linkchecker;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class App {
	private static Logger logger = LoggerFactory.getLogger(App.class);
	private static int timeout = 30;

	private final BlockingDeque<HttpClient> clients;
	private final Map<URI, Integer> statuses;
	private final Queue<URI> urls;
	private final Map<URI, Set<URI>> reverseLinks;
	private final ExecutorService executor;
	private final List<Future> running;

	public App(List<String> urls, int numThreads) {
		this.urls = new ConcurrentLinkedQueue<>();
		for (String url : urls) {
			this.urls.add(URI.create(url));
		}
		statuses = new ConcurrentHashMap<>();
		reverseLinks = new ConcurrentHashMap<>();
		executor = Executors.newFixedThreadPool(numThreads);
		running = new LinkedList<>();
		clients = new LinkedBlockingDeque<>(numThreads);

		for (int i = 0; i < numThreads; i++) {
			clients.offer(HttpClient.newHttpClient());
		}
	}

	public Map<URI, Integer> run() throws InterruptedException, ExecutionException {
		while (urls.size() > 0 || running.size() > 0) {
			if (urls.size() == 0) {
				logger.trace("Waiting for " + running.size() + " jobs to finish ...");
				running.remove(0).get();
				continue;
			}
			final URI url = urls.remove();
			statuses.put(url, -1);
			HttpClient l = clients.take();
			running.add(
				executor.submit(
					() -> {
						try {
							logger.trace("OPENING " + url);

							HttpRequest request = HttpRequest.newBuilder()
								.uri(url)
								.timeout(Duration.ofSeconds(timeout))
								.build();
							HttpResponse<String> response = l.send(request, HttpResponse.BodyHandlers.ofString());
							int status = response.statusCode();
							statuses.put(url, status);
							if (status != 200) {
								logger.warn("Got status " + status + " at " + url);
							} else {
								logger.trace("Got status " + status + " at " + url);
							}

							if (status == 200) {
								if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/html")) {
									Document d = Jsoup.parse(response.body());
									Elements links = d.select("a[href]");
									logger.trace("Found " + links.size() + " a[href] on " + url);
									for (Element link : links) {
										String linkedUrl = link.attr("href");
										try {
											URI uri = URI.create(linkedUrl);
											if (uri.getPath() == null && uri.getScheme() == null) {
												logger.trace("Ignoring uri without path: " + linkedUrl);
												continue;
											}

											if (uri.getScheme() != null && !uri.getScheme().equals("https") && !uri.getScheme().equals("http")) {
												logger.trace("Ignoring uri with schema " + uri.getScheme());
												continue;
											}

											if (uri.getHost() == null || uri.getScheme() == null) {
												uri = URI.create(url.getScheme() + "://" + url.getHost() + (url.getPort() > 0 ? ":" + url.getPort() : "") + linkedUrl);
											}

											if (uri.getHost() == null) {
												logger.debug("Invalid URL: " + uri + " (mentioned at " + url + ")");
												continue;
											}

											if (!uri.getHost().equalsIgnoreCase(url.getHost())) {
												logger.trace("Not following external url: " + uri);
												continue;
											}

											if (!statuses.containsKey(uri)) {
												synchronized (urls) {
													logger.trace("Found url: " + uri);
													urls.add(uri);

													// not synchronizing here, because already synchronized
													if (!reverseLinks.containsKey(uri)) {
														reverseLinks.put(uri, new HashSet<>());
													}
													reverseLinks.get(uri).add(url);
												}
											}
										} catch (IllegalArgumentException e) {
											logger.warn("Invalid uri '" + linkedUrl + "' (" + e.getMessage() + "); mentioned at " + url);
										}
									}
								} else {
									logger.info("Not following links in content type " + response.headers().firstValue("Content-Type").orElse("UNKNOWN"));
								}
							}
						} catch (IOException e) {
							e.printStackTrace();
							statuses.put(url, 0);
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (NullPointerException npe) {
							npe.printStackTrace();
							throw npe;
						} finally {
							clients.offer(l);
						}
					}
				)
			);
		}

		executor.shutdown();
		return statuses;
	}

	public static void main(String args[]) throws InterruptedException, ExecutionException {
		for (Map.Entry r : new App(Arrays.asList(args), 40).run().entrySet()) {
			logger.debug(r.getKey() + ": " + r.getValue());
		}
	}
}

package nl.melp.linkchecker;

import nl.melp.linkchecker.Fetcher.Result;
import nl.melp.linkchecker.backend.InMemory;
import nl.melp.redis.Redis;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class LinkCheckerTest {
	private static Logger logger = LoggerFactory.getLogger(LinkCheckerTest.class);

	private static class MockStatus extends InMemory {
		public MockStatus(RunConfig config) {
			super(logger, config);
		}

		public MockStatus(RunConfig config, Status previousStatus) {
			super(
				logger,
				config,
				previousStatus.statuses,
				previousStatus.urls,
				previousStatus.reverseLinks,
				previousStatus.invalidUrls
			);
		}

		@Override
		public synchronized void add(Result fetched) {
			super.add(fetched);
		}
	}

	private static class MockRunConfig extends RunConfig {
		public MockRunConfig(String... rawArgs) throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
			super(logger, rawArgs);
		}
	}

	private static class MockFetcher extends Fetcher {
		private final Map<String, Result> stubs;
		private final long sleep;
		private final Map<URI, AtomicInteger> fetchCounts = new HashMap<>();


		public MockFetcher(long sleep, Logger logger, Map<String, Result> stubs) {
			super(logger, null, null, null);
			this.sleep = sleep;
			this.stubs = stubs;
		}

		@Override
		public Result fetch(CloseableHttpClient httpClient, URI url) {
			synchronized (fetchCounts) {
				if (!fetchCounts.containsKey(url)) {
					fetchCounts.put(url, new AtomicInteger(0));
				}
				fetchCounts.get(url).incrementAndGet();
			}

			if (this.sleep > 0) {
				try {
					Thread.sleep(this.sleep);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			if (!stubs.containsKey(url.toString())) {
				throw new IllegalStateException("Invalid stub requested: " + url);
			}
			return stubs.get(url.toString());
		}

		public MockFetcher setStatus(URI s, int statusCode) {
			final String strUrl = s.toString();
			stubs.put(strUrl, new Fetcher.Result(s, statusCode, stubs.get(strUrl).getReferredLinks(), stubs.get(strUrl).getInvalidLinks()));
			return this;
		}

		public boolean remove(URI uri, String invalidLink) {
			return stubs.get(uri.toString()).getInvalidLinks().remove(invalidLink);
		}

		public int getFetchCount(URI uri) {
			return fetchCounts.containsKey(uri) ? fetchCounts.get(uri).get() : 0;
		}

		public Map<URI, AtomicInteger> getFetchCounts() {
			return fetchCounts;
		}
	}

	@Test
	public void testRunInMemory() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException, InterruptedException {
		MockStatus initialState = new MockStatus(new MockRunConfig());

		testRunWithInitialState(initialState);
		testRunWithInitialState(initialState);
	}

	@Test
	public void testRunInRedis() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException, InterruptedException, IOException {
		try (Redis.Managed redis = Redis.connect("localhost", 6379)) {
			Status redisState = new MockRunConfig().createStatus(redis);

			testRunWithInitialState(redisState);
			testRunWithInitialState(redisState);
		}
	}

	private void testRunWithInitialState(Status initialState) throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException, InterruptedException {
		MockRunConfig config = new MockRunConfig("--reset", "http://localhost:8080");
		MockStatus status = new MockStatus(config, initialState);
		final MockFetcher fetcher = new MockFetcher(
			1L,
			logger,
			new HashMap<>() {{
				put(
					"http://localhost:8080/",
					new Result(
						URI.create("http://localhost:8080/"),
						200,
						new HashSet<>(Set.of(URI.create("http://localhost:8080/"), URI.create("http://localhost:8080/abc"), URI.create("http://localhost:8080/xyz"))),
						new HashSet<>()
					)
				);
				put(
					"http://anotherhost/",
					new Result(
						URI.create("http://anotherhost/"),
						404,
						null,
						null
					)
				);
				put(
					"http://anotherhost/somelink",
					new Result(
						URI.create("http://anotherhost/somelink"),
						500,
						null,
						null
					)
				);
				put(
					"http://localhost:8080/abc",
					new Result(
						URI.create("http://localhost:8080/abc"),
						200,
						new HashSet<>(Set.of(URI.create("http://localhost:8080/"))),
						new HashSet<>()
					)
				);
				put(
					"http://localhost:8080/xyz",
					new Result(
						URI.create("http://localhost:8080/xyz"),
						200,
						new HashSet<>(
							Set.of(
								URI.create("http://localhost:8080/"),
								URI.create("http://localhost:8080/xyz/1"),
								URI.create("http://localhost:8080/xyz/2")
							)
						),
						new HashSet<>(Set.of("(invalid)"))
					)
				);
				put(
					"http://localhost:8080/xyz/1",
					new Result(
						URI.create("http://localhost:8080/xyz/1"),
						200,
						new HashSet<>(Set.of(URI.create("http://anotherhost/"))),
						new HashSet<>()
					)
				);
				put(
					"http://localhost:8080/xyz/2",
					new Result(
						URI.create("http://localhost:8080/xyz/2"),
						200,
						new HashSet<>(Set.of(URI.create("http://anotherhost/"), URI.create("http://anotherhost/somelink"))),
						new HashSet<>()
					)
				);
			}}
		);

		// ---------------------------------------------------------------------------------------------------------
		// ONLY INTERNAL
		// ---------------------------------------------------------------------------------------------------------
		LinkChecker c = new LinkChecker(
			config,
			status,
			fetcher
		);

		Assert.assertEquals(1, status.urls.size());
		Assert.assertTrue(status.urls.contains(URI.create("http://localhost:8080/")));
		Assert.assertFalse(config.shouldFollowLinks(URI.create("http://localhost:8080/xyz/2"), URI.create("http://anotherhost/")));
		Assert.assertEquals(0, status.invalidUrls.size());

		c.run();

		Assert.assertEquals(5, status.statuses.size());
		Assert.assertEquals(1, status.invalidUrls.size());

		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/1")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/2")));
		Assert.assertNull(status.statuses.get(URI.create("http://anotherhost/")));
		Assert.assertNull(status.statuses.get(URI.create("http://anotherhost/somelink")));

		Assert.assertEquals(1, fetcher.getFetchCount(URI.create("http://localhost:8080/")));
		Assert.assertEquals(1, fetcher.getFetchCount(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(1, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz")));

		Assert.assertEquals(0, fetcher.getFetchCount(URI.create("http://anotherhost/")));
		Assert.assertEquals(0, fetcher.getFetchCount(URI.create("http://anotherhost/somelink")));

		Assert.assertEquals(1, status.invalidUrls.size());

		// ---------------------------------------------------------------------------------------------------------
		// RERUN WITH FOLLOW
		// ---------------------------------------------------------------------------------------------------------
		config = new MockRunConfig("--reset", "http://localhost:8080", "--follow-from-local");
		status = new MockStatus(config, status);

		c = new LinkChecker(
			config,
			status,
			fetcher
		);
		Assert.assertEquals(1, status.urls.size());
		Assert.assertEquals(0, status.invalidUrls.size());

		Assert.assertTrue(status.urls.contains(URI.create("http://localhost:8080/")));
		Assert.assertTrue(config.shouldFollowLinks(URI.create("http://localhost:8080/xyz/2"), URI.create("http://anotherhost/")));
		c.run();

		Assert.assertEquals(1, status.invalidUrls.size());
		Assert.assertTrue(status.invalidUrls.containsKey(URI.create("http://localhost:8080/xyz")));
		Assert.assertTrue(status.invalidUrls.get(URI.create("http://localhost:8080/xyz")).contains("(invalid)"));

		Assert.assertEquals(7, status.statuses.size());
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/1")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/2")));
		Assert.assertEquals(404, (int)status.statuses.get(URI.create("http://anotherhost/")));
		Assert.assertEquals(500, (int)status.statuses.get(URI.create("http://anotherhost/somelink")));

		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://localhost:8080/")));
		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz")));

		Assert.assertEquals(1, fetcher.getFetchCount(URI.create("http://anotherhost/")));
		Assert.assertEquals(1, fetcher.getFetchCount(URI.create("http://anotherhost/somelink")));

		// ---------------------------------------------------------------------------------------------------------
		// RECHECK
		// ---------------------------------------------------------------------------------------------------------
		config = new MockRunConfig("--recheck", "http://localhost:8080", "--follow-from-local");
		status = new MockStatus(config, status);
		c = new LinkChecker(
			config,
			status,
			fetcher
		);

		Assert.assertEquals(0, status.invalidUrls.size());

		final HashSet<URI> queuedItems = new HashSet<>(status.urls);
		Assert.assertEquals(3, queuedItems.size());
		Assert.assertTrue(queuedItems.contains(URI.create("http://localhost:8080/xyz")));
		Assert.assertTrue(queuedItems.contains(URI.create("http://localhost:8080/xyz/1")));
		Assert.assertTrue(queuedItems.contains(URI.create("http://localhost:8080/xyz/2")));

		fetcher.setStatus(URI.create("http://anotherhost/"), 200);
		fetcher.setStatus(URI.create("http://anotherhost/somelink"), 200);

		Assert.assertEquals(2, status.statuses.size());
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/abc")));

		Assert.assertFalse(status.statuses.containsKey(URI.create("http://anotherhost/")));
		Assert.assertFalse(status.statuses.containsKey(URI.create("http://anotherhost/somelink")));

		c.run();

		Assert.assertEquals(1, status.invalidUrls.size());
		Assert.assertTrue(status.invalidUrls.containsKey(URI.create("http://localhost:8080/xyz")));
		Assert.assertTrue(status.invalidUrls.get(URI.create("http://localhost:8080/xyz")).contains("(invalid)"));

		Assert.assertEquals(7, status.statuses.size());
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/1")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/2")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/somelink")));

		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://localhost:8080/")));
		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(3, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz")));
		Assert.assertEquals(3, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz/1")));
		Assert.assertEquals(3, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz/2")));

		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://anotherhost/")));
		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://anotherhost/somelink")));

		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/somelink")));

		// ---------------------------------------------------------------------------------------------------------
		// RECHECK after invalid link is fixed
		// ---------------------------------------------------------------------------------------------------------
		config = new MockRunConfig("--recheck", "http://localhost:8080", "--follow-from-local");
		status = new MockStatus(config, status);
		Assert.assertTrue(fetcher.remove(URI.create("http://localhost:8080/xyz"), "(invalid)"));
		c = new LinkChecker(
			config,
			status,
			fetcher
		);
		Assert.assertEquals(1, status.urls .size());
		c.run();

		Assert.assertEquals(7, status.statuses.size());
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/1")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/2")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/somelink")));

		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://localhost:8080/")));
		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(4, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz")));
		Assert.assertEquals(3, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz/1")));
		Assert.assertEquals(3, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz/2")));

		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://anotherhost/")));
		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://anotherhost/somelink")));

		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/somelink")));
		// ---------------------------------------------------------------------------------------------------------
		// RECHECK after all links fixed
		// ---------------------------------------------------------------------------------------------------------
		config = new MockRunConfig("--recheck", "http://localhost:8080");
		status = new MockStatus(config, status);
		c = new LinkChecker(
			config,
			status,
			fetcher
		);
		Assert.assertEquals(0, status.urls.size());
		c.run();

		Assert.assertEquals(7, status.statuses.size());
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/1")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://localhost:8080/xyz/2")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/somelink")));

		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://localhost:8080/")));
		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://localhost:8080/abc")));
		Assert.assertEquals(4, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz")));
		Assert.assertEquals(3, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz/1")));
		Assert.assertEquals(3, fetcher.getFetchCount(URI.create("http://localhost:8080/xyz/2")));

		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://anotherhost/")));
		Assert.assertEquals(2, fetcher.getFetchCount(URI.create("http://anotherhost/somelink")));

		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/")));
		Assert.assertEquals(200, (int)status.statuses.get(URI.create("http://anotherhost/somelink")));
	}

	@Test
	public void testRunConcurrent() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException, InterruptedException {
		final MockRunConfig config = new MockRunConfig("--reset", "http://localhost:8080");
		final MockStatus status = new MockStatus(config);

		Set<URI> urls = new HashSet<>();
		for (int i = 0; i < 1500; i ++) {
			urls.add(URI.create("http://localhost:8080/" + randomString()));
		}

		LinkChecker c = new LinkChecker(
			config,
			status,
			new MockFetcher(0L, logger, getStubs(urls))
		);

		c.run();

		status.statuses.forEach((k, v) -> {
			System.out.println("[" + v + "] " + k);
		});
		Assert.assertTrue(status.statuses.keySet().containsAll(urls));
		final Set<URI> copy = new HashSet<>(urls);
		copy.add(URI.create("http://localhost:8080/"));
		Assert.assertTrue(copy.containsAll(status.statuses.keySet()));
		Assert.assertEquals(urls.size() + 1, status.statuses.size());
	}

	private HashMap<String, Result> getStubs(Set<URI> urls) {
		return new HashMap<>() {{
			put(
				"http://localhost:8080/",
				new Result(
					URI.create("http://localhost:8080/"),
					200,
					new HashSet<>(urls),
					new HashSet<>(
						Set.of(
							"(invalid)"
						)
					)
				)
			);
			for (URI url : urls) {
				put(
					url.toString(),
					new Result(
						url,
						200,
						new HashSet<>(urls),
						new HashSet<>(Set.of(
							"(invalid)"
						))
					)
				);
			}
		}};
	}

	@Test
	public void testRunConcurrentWithRedisBackend() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException, InterruptedException, IOException {
		final MockRunConfig config = new MockRunConfig("--reset", "http://localhost:8080");
		try (Redis.Managed redis = Redis.connect("localhost", 6379)) {
			final Status status = config.createStatus(redis);
			Set<URI> urls = new HashSet<>();
			for (int i = 0; i < 300; i++) {
				urls.add(URI.create("http://localhost:8080/" + randomString()));
			}

			LinkChecker c = new LinkChecker(
				config,
				status,
				new MockFetcher(
					0L,
					logger,
					new HashMap<>() {{
						put(
							"http://localhost:8080/",
							new Fetcher.Result(
								URI.create("http://localhost:8080/"),
								200,
								urls,
								Set.of(
									"(invalid)"
								)
							)
						);
						int i = 0;
						for (URI url : urls) {
							put(
								url.toString(),
								new Fetcher.Result(
									url,
									200,
									urls,
									Set.of(
										"(invalid)"
									)
								)
							);
						}
					}}
				)
			);

			c.run();

			status.statuses.forEach((k, v) -> {
				System.out.println("[" + v + "] " + k);
			});
			Assert.assertEquals(1 + urls.size(), status.statuses.size());
		}
	}

	private static final Random random = new Random();
	private String randomString() {
		int leftLimit = 97; // letter 'a'
		int rightLimit = 122; // letter 'z'
		int targetStringLength = 10;
		return random.ints(leftLimit, rightLimit + 1)
			.limit(targetStringLength)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
	}
}

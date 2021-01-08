package nl.melp.linkchecker;

import nl.melp.redis.Redis;
import nl.melp.redis.collections.ISerializer;
import nl.melp.redis.collections.SerializedHashMap;
import nl.melp.redis.collections.Serializers;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.slf4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class RunConfig {
	private final Map<String, Set<String>> opts;
	private final Set<String> flags;
	private final List<String> args;
	private final Set<String> localHosts = new HashSet<>();
	private final String redisHost;
	private final int redisPort;
	private final Logger logger;
	private final PoolingHttpClientConnectionManager connectionManager;

	public RunConfig(Logger logger, String... rawArgs) throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
		this.logger = logger;

		// Crude argument parsing
		this.opts = new HashMap<>();
		this.flags = new HashSet<>();
		this.args = new LinkedList<>();

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

		redisHost = opts.getOrDefault("redis-host", Collections.emptySet()).stream().findFirst().orElse(System.getProperty("redis.host", "localhost"));
		redisPort = Integer.parseInt(opts.getOrDefault("redis-port", Collections.emptySet()).stream().findFirst().orElse(System.getProperty("redis.port", "6379")));

		for (String startUri : args) {
			localHosts.add(URI.create(startUri).getHost());
		}

		if (isIgnoreSslErrors()) {
			final SSLContext sslContext = new SSLContextBuilder()
				.loadTrustMaterial(null, (x509CertChain, authType) -> true)
				.build();

			connectionManager = new PoolingHttpClientConnectionManager(
				RegistryBuilder.<ConnectionSocketFactory>create()
					.register("http", PlainConnectionSocketFactory.INSTANCE)
					.register("https", new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE))
					.build()
			);
		} else {
			connectionManager = new PoolingHttpClientConnectionManager(); // default
		}
	}

	public List<String> getArgs() {
		return args;
	}

	public boolean hasFlag(String flag) {
		return flags.contains(flag);
	}

	public String getRedisHost() {
		return redisHost;
	}

	public int getRedisPort() {
		return redisPort;
	}

	public boolean shouldFollowLinks(URI context, URI url) {
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
		if (hasFlag("follow-from-local")) {
			return localHosts.contains(context.getHost());
		}
		return false;
	}

	public boolean shouldExtractLinks(URI context) {
		return !flags.contains("no-follow") && localHosts.contains(context.getHost());
	}

	public int getNumThreads() {
		return opts.containsKey("threads") ? Integer.parseInt(opts.get("threads").stream().findFirst().orElse("40")) : 40;
	}

	public int getDelayMs() {
		return opts.containsKey("delay-ms") ? Integer.parseInt(opts.get("delay-ms").stream().findFirst().orElse("0")) : 0;
	}

	public boolean isIgnoreSslErrors() {
		return hasFlag("ignore-ssl-errors");
	}

	public Status createStatus(Redis redis) {
		return new nl.melp.linkchecker.backend.Redis(
			redis,
			logger,
			this
		);
	}

	public Redis.Managed connect() throws IOException {
		return Redis.connect(getRedisHost(), getRedisPort());
	}

	public void report(Redis redis, LinkChecker linkchecker) {
		if (hasFlag("report")) {
			ISerializer<String> stringSerializer = Serializers.of(String.class);
			ISerializer<Integer> intSerializer = Serializers.of(Integer.class);

			//noinspection MismatchedQueryAndUpdateOfCollection
			SerializedHashMap<String, Integer> report = new SerializedHashMap<>(
				stringSerializer,
				intSerializer,
				redis, LinkChecker.class.getCanonicalName() + ".report.statuses");

			//noinspection MismatchedQueryAndUpdateOfCollection
			SerializedHashMap<String, String> refers = new SerializedHashMap<>(
				stringSerializer,
				stringSerializer,
				redis, LinkChecker.class.getCanonicalName() + ".report.referers");

			logger.info("Building report for " + linkchecker.status.statuses.size() + " keys and " + linkchecker.status.invalidUrls.size() + " invalids");
			report.clear();
			refers.clear();

			linkchecker.status.results.forEach((k, v) -> {
				if (hasFlag("report-all") || LinkChecker.isErrorStatus(v)) {
					report.put(k.toString(), v);
					refers.put(k.toString(), linkchecker.status.reverseLinks.get(k).stream().map(URI::toString).collect(Collectors.joining("\n")));
				}
			});
			Map<String, Set<URI>> inverse = new HashMap<>();

			linkchecker.status.invalidUrls.forEach((uri, links) -> {
				for (String link : links) {
					if (!inverse.containsKey(link)) {
						inverse.put(link, new HashSet<>());
					}
					inverse.get(link).add(uri);
				}
			});
			inverse.forEach((s, uri) -> {
				report.put(s, 0);
				refers.put(s, uri.stream().map(URI::toString).collect(Collectors.joining("\n")));
			});

			linkchecker.status.report(hasFlag("report-all"));
		}
	}

	public CloseableHttpClient createHttpClient() {
		return HttpClients.createMinimal(connectionManager);
	}
}

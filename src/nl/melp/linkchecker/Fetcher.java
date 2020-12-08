package nl.melp.linkchecker;

import nl.melp.linkchecker.URIResolver.InvalidURIException;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

public class Fetcher {
	private static final int timeout = 30;

	private static final RequestConfig requestConfig = RequestConfig.custom()
		.setConnectTimeout(timeout * 1000)
		.setConnectionRequestTimeout(timeout * 1000)
		.setSocketTimeout(timeout * 1000)
		.build();

	public static class Result {
		private final URI uri;
		private final int statusCode;
		private final Set<URI> referredLinks;
		private final Set<String> invalidLinks;

		public Result(URI uri, int statusCode, Set<URI> referredLinks, Set<String> invalidLinks) {
			this.uri = uri;
			this.statusCode = statusCode;
			this.referredLinks = referredLinks;
			this.invalidLinks = invalidLinks;
		}

		public URI getUri() {
			return uri;
		}

		public int getStatusCode() {
			return statusCode;
		}

		public Set<URI> getReferredLinks() {
			return referredLinks;
		}

		public Set<String> getInvalidLinks() {
			return invalidLinks;
		}
	}

	private final Logger logger;
	private final RunConfig config;
	private final Extractor extractor;
	private final URIResolver resolver;

	public Fetcher(Logger logger, RunConfig config, Extractor extractor, URIResolver resolver) {
		this.logger = logger;
		this.config = config;
		this.extractor = extractor;
		this.resolver = resolver;
	}

	public Result fetch(CloseableHttpClient httpClient, URI url) {
		var request = new HttpGet(url);
		request.setConfig(requestConfig);
		try (CloseableHttpResponse response = httpClient.execute(request)) {
			int statusCode = response.getStatusLine().getStatusCode();

			logger.trace("Got status " + statusCode + " at " + url);

			HttpEntity responseEntity = response.getEntity();
			Set<URI> links = new LinkedHashSet<>();
			Set<String> invalidLinks = new LinkedHashSet<>();

			if (config.shouldExtractLinks(url)) {
				for (String link : extractor.extract(url, statusCode, response, responseEntity)) {
					try {
						final URI target = resolver.resolveUri(url, link);
						if (target != null) {
							links.add(target);
						}
					} catch (InvalidURIException e) {
						invalidLinks.add(link);
					}
				}
			}
			return new Result(url, statusCode, links, invalidLinks);
		} catch (IOException e) {
			return new Result(url, 0, null, null);
		}
	}
}

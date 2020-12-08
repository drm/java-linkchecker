package nl.melp.linkchecker;

import org.slf4j.Logger;

import java.net.URI;

public class URIResolver {
	private final Logger logger;

	public static class InvalidURIException extends Exception {
		private final URI context;
		private final String linkedUrl;
		private final String reason;

		public InvalidURIException(URI context, String linkedUrl, String reason) {
			this.context = context;
			this.linkedUrl = linkedUrl;
			this.reason = reason;
		}

		public URI getContext() {
			return context;
		}

		public String getLinkedUrl() {
			return linkedUrl;
		}

		public String getReason() {
			return reason;
		}
	}

	public URIResolver(Logger logger) {
		this.logger = logger;
	}

	public URI resolveUri(URI context, String linkedUrl) throws InvalidURIException {
		URI uri;
		if (linkedUrl.isBlank()) {
			// According to the RFC, and empty link resolves to the top of the current document;
			// see https://stackoverflow.com/questions/5637969/is-an-empty-href-valid
			uri = context;
		} else {
			try {
				uri = context.resolve(linkedUrl);
				logger.trace("Link from " + context + " to '" + linkedUrl + " resolved to '" + uri + "'");
			} catch (IllegalArgumentException e) {
				throw new InvalidURIException(context, linkedUrl, e.getMessage());
			}
		}

		if (!"".equals(uri.getFragment()) && uri.getFragment() != null) {
			uri = URI.create(uri.toString().replace("#" + uri.getRawFragment(), ""));
		}

		if (uri.getPath() == null && uri.getScheme() == null) {
			throw new InvalidURIException(context, linkedUrl, "Ignoring uri without path: " + linkedUrl);
		}

		if (uri.getScheme() != null && !uri.getScheme().equals("https") && !uri.getScheme().equals("http")) {
			return null;
		}

		if (uri.getHost() == null || uri.getScheme() == null) {
			uri = URI.create(context.getScheme() + "://" + context.getHost() + (context.getPort() > 0 ? ":" + context.getPort() : "") + linkedUrl);
		}

		if (uri.getHost() == null) {
			throw new InvalidURIException(context, linkedUrl, "Could not extract host");
		}

		if (uri.getPath() == null) {
			logger.trace("Not following non-path url: " + uri);
			return null;
		}
		return uri;
	}
}

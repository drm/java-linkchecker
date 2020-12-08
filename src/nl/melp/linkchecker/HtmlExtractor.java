package nl.melp.linkchecker;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

public class HtmlExtractor implements Extractor {
	private final Logger logger;

	public HtmlExtractor(Logger logger) {
		this.logger = logger;
	}

	@Override
	public Set<String> extract(URI url, int statusCode, CloseableHttpResponse response, HttpEntity responseEntity) throws IOException {
		Set<String> referred = new LinkedHashSet<>();
		Header contentTypeHeader = response.getFirstHeader("Content-Type");
		String contentType = contentTypeHeader == null ? "UNKNOWN" : contentTypeHeader.getValue();

		if (statusCode == 200) {
			if (contentType.startsWith("text/html")) {
				Document d = Jsoup.parse(responseEntity.getContent(), "UTF-8", url.toString());
				Elements links = d.select("a[href]");
				logger.trace("Found " + links.size() + " on " + url);
				for (Element link : links) {
					referred.add(link.attr("href"));
				}
			} else {
				logger.trace("Not following links in content type " + contentType);
			}
		} else if (response.getFirstHeader("Location") != null) {
			String location = response.getFirstHeader("Location").getValue();
			if (referred.add(location)) {
				logger.trace("Following redirect (" + statusCode + ") [" + url + " => " + location + "]");
			}
		} else {
			logger.debug("Skipping {}, content-type: {}", url, contentType);
		}
		return referred;

	}
}

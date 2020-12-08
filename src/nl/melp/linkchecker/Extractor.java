package nl.melp.linkchecker;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

public interface Extractor {
	Set<String> extract(URI url, int statusCode, CloseableHttpResponse response, HttpEntity responseEntity) throws IOException;
}

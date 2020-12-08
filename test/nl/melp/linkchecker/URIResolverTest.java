package nl.melp.linkchecker;

import nl.melp.linkchecker.URIResolver.InvalidURIException;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class URIResolverTest {
	@Test
	public void testResolution() throws InvalidURIException {
		var resolver = new URIResolver(LoggerFactory.getLogger(URIResolverTest.class));

		String[] hostPrefixes = new String[]{"https://example.org", "http://localhost", "http://localhost:8080"};

		for (String prefix : hostPrefixes) {
			Assert.assertEquals(URI.create(prefix + "/"), resolver.resolveUri(URI.create(prefix), "/"));
			Assert.assertEquals(URI.create(prefix + "/index.html"), resolver.resolveUri(URI.create(prefix), "/index.html"));
			Assert.assertEquals(URI.create(prefix + "/foo/bar.html"), resolver.resolveUri(URI.create(prefix + "/foo/"), "bar.html"));
			Assert.assertEquals(URI.create(prefix + "/foo/x/bar.html"), resolver.resolveUri(URI.create(prefix + "/foo/"), "x/bar.html"));
			Assert.assertEquals(URI.create(prefix + "/foo/x/bar.html"), resolver.resolveUri(URI.create(prefix + "/foo/"), "/foo/x/bar.html"));
			Assert.assertEquals(URI.create(prefix + "/bar.html"), resolver.resolveUri(URI.create(prefix + "/foo/"), "../bar.html"));
			Assert.assertEquals(URI.create(prefix + "/bar.html"), resolver.resolveUri(URI.create(prefix + "/bar.html"), ""));
			Assert.assertEquals(URI.create(prefix + "/bar.html"), resolver.resolveUri(URI.create(prefix + "/bar.html#asdf"), ""));
			Assert.assertEquals(URI.create(prefix + "/bar.html"), resolver.resolveUri(URI.create(prefix + "/bar.html"), "#asdf"));
			Assert.assertEquals(URI.create(prefix + "/foo.html"), resolver.resolveUri(URI.create(prefix + "/bar.html"), "foo.html#asdf"));
			Assert.assertEquals(URI.create(prefix + "/bar.html"), resolver.resolveUri(URI.create(prefix + "/foo"), "bar.html"));
			if (prefix.startsWith("https:")) {
				Assert.assertEquals(URI.create("https://example.org"), resolver.resolveUri(URI.create(prefix + "/foo"), "//example.org"));
			} else {
				Assert.assertEquals(URI.create("http://example.org"), resolver.resolveUri(URI.create(prefix + "/foo"), "//example.org"));
			}
			Assert.assertEquals(URI.create("http://example.org"), resolver.resolveUri(URI.create(prefix + "/foo"), "http://example.org"));
		}

		String[] unresolvable = new String[]{"mailto:foo"};

		for (String link : unresolvable) {
			Assert.assertNull(resolver.resolveUri(URI.create("http://localhost"), link));
		}

		String[] errors = new String[]{" abc "};

		for (String error : errors) {
			try {
				URI resolved = resolver.resolveUri(URI.create("http://localhost"), error);
				Assert.fail("Expected resolving '" + error + "' to throw error, in stead got: " + resolved);
			} catch (InvalidURIException ignored) {
			}
		}
	}
}

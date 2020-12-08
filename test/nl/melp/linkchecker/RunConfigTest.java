package nl.melp.linkchecker;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

public class RunConfigTest {
	private static Logger logger = LoggerFactory.getLogger(RunConfigTest.class);
	@Test
	public void testFlags() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
		RunConfig r = new RunConfig(logger, "http://localhost:8080");
		Assert.assertFalse(r.shouldFollowLinks(URI.create("http://localhost:8080"), URI.create("http://anotherhost")));
		Assert.assertTrue(r.shouldExtractLinks(URI.create("http://localhost:8080")));
	}
}

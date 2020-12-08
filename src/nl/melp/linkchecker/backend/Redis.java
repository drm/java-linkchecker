package nl.melp.linkchecker.backend;

import nl.melp.linkchecker.LinkChecker;
import nl.melp.linkchecker.RunConfig;
import nl.melp.linkchecker.Status;
import nl.melp.redis.collections.ISerializer;
import nl.melp.redis.collections.SerializedHashMap;
import nl.melp.redis.collections.SerializedMappedSet;
import nl.melp.redis.collections.SerializedSortedSet;
import nl.melp.redis.collections.Serializers;
import org.slf4j.Logger;

import java.net.URI;

public class Redis extends Status {
	private static class URISerializer implements ISerializer<URI> {
		ISerializer<String> innerSerializer = Serializers.of(String.class);

		@Override
		public byte[] serialize(URI uri) {
			return innerSerializer.serialize(uri.toString());
		}

		@Override
		public URI deserialize(byte[] bytes) {
			return URI.create(innerSerializer.deserialize(bytes));
		}
	}

	private static final URISerializer uriSerializer = new URISerializer();

	public Redis(nl.melp.redis.Redis redis, Logger logger, RunConfig config) {
		super(
			logger,
			config,
			new SerializedHashMap<>(uriSerializer, Serializers.of(Integer.class), redis, prefixKeyName("statuses")),
			new SerializedSortedSet<>(uriSerializer, redis, prefixKeyName("urls")),
			new SerializedMappedSet<>(uriSerializer, uriSerializer, redis, prefixKeyName("reverseLinks")),
			new SerializedMappedSet<>(uriSerializer, Serializers.of(String.class), redis, prefixKeyName("invalidUrls"))
		);
	}

	public static String prefixKeyName(String s) {
		return String.format("%s.%s", LinkChecker.class.getCanonicalName(), s);
	}
}

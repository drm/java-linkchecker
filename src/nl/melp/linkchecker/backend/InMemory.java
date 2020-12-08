package nl.melp.linkchecker.backend;

import nl.melp.linkchecker.Fetcher.Result;
import nl.melp.linkchecker.RunConfig;
import nl.melp.linkchecker.Status;
import org.slf4j.Logger;

import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemory extends Status {
	private static class MappedSet<K, V> extends ConcurrentHashMap<K, Set<V>> {
		@Override
		public Set<V> get(Object key) {
			Object ret = super.get(key);
			if (ret == null) {
				ret = newKeySet();
				this.put((K)key, (Set<V>)ret);
			}
			return (Set<V>)ret;
		}
	}

	private static class SetQueue<K> extends LinkedList<K> implements Set<K> {
		private Set<K> s = new LinkedHashSet<>();

		@Override
		public Iterator<K> iterator() {
			AtomicInteger i = new AtomicInteger(0);
			return new Iterator<>() {
				@Override
				public boolean hasNext() {
					return i.get() < size();
				}

				@Override
				public K next() {
					return get(i.getAndIncrement());
				}
			};
		}

		@Override
		public boolean add(K k) {
			// ensure uniqueness.
			if (s.add(k)) {
				return super.add(k);
			}
			return false;
		}

		@Override
		public boolean remove(Object o) {
			if (s.remove(o)) {
				return super.remove(o);
			}
			return false;
		}

		@Override
		public void clear() {
			s.clear();
			super.clear();
		}
	}

	public InMemory(Logger logger, RunConfig config) {
		this(
			logger,
			config,
			new HashMap<>(),
			new SetQueue<>(),
			new MappedSet<>(),
			new MappedSet<>()
		);
	}

	protected InMemory(Logger logger, RunConfig config, Map<URI, Integer> statuses, Set<URI> urls, Map<URI, Set<URI>> reverseLinks, Map<URI, Set<String>> invalidUrls) {
		super(logger, config, statuses, urls, reverseLinks, invalidUrls);
	}



	@Override
	public synchronized void add(Result fetch) {
		super.add(fetch);
	}
}

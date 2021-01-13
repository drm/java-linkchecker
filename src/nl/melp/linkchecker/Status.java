package nl.melp.linkchecker;

import nl.melp.linkchecker.Fetcher.Result;
import org.slf4j.Logger;

import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Status {
	private final Logger logger;
	public final Map<URI, Integer> statuses;
	public final Set<URI> urls;
	public final Map<URI, Set<URI>> reverseLinks;
	public final Map<URI, Set<String>> invalidUrls;
	private final RunConfig config;

	public Status(Logger logger, RunConfig config, Map<URI, Integer> statuses, Set<URI> urls, Map<URI, Set<URI>> reverseLinks, Map<URI, Set<String>> invalidUrls) {
		this.config = config;
		this.logger = logger;
		this.statuses = statuses;
		this.urls = urls;
		this.reverseLinks = reverseLinks;
		this.invalidUrls = invalidUrls;

		Set<URI> startUrls = new HashSet<>();
		if (config.hasFlag("reset")) {
			clear();

			for (String arg : config.getArgs()) {
				URI uri = URI.create(arg);
				if (uri.getPath() == null || uri.getPath().equals("")) {
					uri = uri.resolve("/");
				}
				startUrls.add(uri);
			}
		} else if (!config.hasFlag("no-recheck") && (config.hasFlag("resume") || config.hasFlag("recheck"))) {
			if (config.hasFlag("recheck")) {
				this.urls.clear();

				Set<URI> resetStatus = new HashSet<>();
				statuses.forEach((k, v) -> {
					if (v >= 400 || v <= 0) {
						// recheck all pages that refer to this link:
						for (URI s : reverseLinks.get(k)) {
							System.out.printf("Link [%d %s] <-- %s [RECHECK]%n", v, k, s);
							startUrls.add(s);
						}
						resetStatus.add(k);
					}
				});
				resetStatus.forEach(statuses::remove);
				Set<URI> mentions = new HashSet<>();
				invalidUrls.forEach((k, v) -> {
					mentions.add(k);
					if (v.size() > 0) {
						startUrls.add(k);
					}
				});
				mentions.forEach(invalidUrls::remove);
			}
		}

		for (URI uri : startUrls) {
			this.statuses.remove(uri);
			this.urls.add(uri);
		}
		if (!config.hasFlag("resume")) {
			for (URI uri : this.urls) {
				this.statuses.remove(uri);
			}
		}
	}

	private void clear() {
		urls.clear();
		statuses.clear();
		reverseLinks.clear();
		invalidUrls.clear();
	}

	public void report(boolean all) {
		for (Map.Entry<URI, Set<String>> entry : invalidUrls.entrySet()) {
			System.out.printf("INVALID url ocurred at: %s - referred by following urls\n", entry.getKey());
			for (String url : invalidUrls.get(entry.getKey())) {
				System.out.printf(" + %s\n", url);
			}
		}

		int numErr = 0;
		int numSuccess = 0;
		for (Map.Entry<URI, Integer> r : statuses.entrySet()) {
			if (r.getValue() >= 400 || r.getValue() <= 0) {
				System.out.printf("[%d] at %s (referred by following urls:)\n", r.getValue(), r.getKey());
				for (URI referredBy : reverseLinks.get(r.getKey())) {
					System.out.printf(" + %s\n", referredBy);
				}
				numErr++;
			} else {
				if (all) {
					System.out.printf("[OK] at %s%n", r.getKey());
				}
				numSuccess++;
			}
		}
		System.out.printf(
			"Success: %d, Errors: %d, Invalids: %d%n",
			numSuccess,
			numErr,
			invalidUrls.size()
		);

		System.out.printf("Total number of resolved statuses: %d%n", numChecked());
	}

	public int numChecked() {
		return statuses.size();
	}

	public int numPending() {
		return urls.size() - statuses.size();
	}

	public int numQueueud() {
		return urls.size();
	}

	public void add(Result fetched) {
		statuses.put(fetched.getUri(), fetched.getStatusCode());
		if (fetched.getStatusCode() > 0) {
			invalidUrls.remove(fetched.getUri());

			if (fetched.getInvalidLinks() != null) {
				for (String link : fetched.getInvalidLinks()) {
					invalidUrls.get(fetched.getUri()).add(link);
				}
			}
			if (fetched.getReferredLinks() != null) {
				for (URI uri : fetched.getReferredLinks()) {
					URI context = fetched.getUri();
					if (uri != null) {
						if (config.shouldFollowLinks(context, uri) && !statuses.containsKey(uri)) {
							urls.add(uri);
						}
						if (context != null) {
							reverseLinks.get(uri).add(context);
						}
					}
				}
			}
		}
	}
}

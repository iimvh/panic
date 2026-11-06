package com.panic.agents.digest.feed;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Canonical form of a story link, used to recognize the same story syndicated by several feeds.
 * Strips the parts that never identify an article (scheme, {@code www}, tracking query parameters,
 * trailing slash) so {@code https://a/story?utm_source=x} and {@code https://www.a/story} match.
 */
public final class CanonicalLink {

	private static final java.util.Set<String> TRACKING = java.util.Set.of("utm_source", "utm_medium",
			"utm_campaign", "utm_term", "utm_content", "fbclid", "gclid", "ref", "zalo");

	private CanonicalLink() {
	}

	public static String of(String link) {
		if (link == null || link.isBlank()) {
			return "";
		}
		try {
			URI uri = URI.create(link.trim());
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			host = host.startsWith("www.") ? host.substring(4) : host;
			String path = uri.getPath() == null ? "" : uri.getPath();
			if (path.endsWith("/") && path.length() > 1) {
				path = path.substring(0, path.length() - 1);
			}
			String query = canonicalQuery(uri.getQuery());
			return host + path + (query.isEmpty() ? "" : "?" + query);
		} catch (IllegalArgumentException ex) {
			return link.trim().toLowerCase(Locale.ROOT);
		}
	}

	private static String canonicalQuery(String query) {
		if (query == null || query.isBlank()) {
			return "";
		}
		return Arrays.stream(query.split("&"))
				.filter(pair -> !pair.isBlank())
				.filter(pair -> !TRACKING.contains(pair.split("=", 2)[0].toLowerCase(Locale.ROOT)))
				.sorted()
				.collect(Collectors.joining("&"));
	}
}

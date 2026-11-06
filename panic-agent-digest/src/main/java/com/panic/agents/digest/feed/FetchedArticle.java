package com.panic.agents.digest.feed;

import java.time.Instant;

/**
 * One article as read from a feed, before it becomes a {@link NewsItem}. Metadata only; the feed
 * reader never parses the article body.
 */
public record FetchedArticle(
		String externalId,
		String title,
		String link,
		String excerpt,
		Instant publishedAt) {

	/** Feed ids are optional: when absent the link is the stable per-source identity. */
	public String identity(String fallbackLink) {
		if (externalId != null && !externalId.isBlank()) {
			return externalId.trim();
		}
		return fallbackLink == null ? "" : fallbackLink.trim();
	}
}

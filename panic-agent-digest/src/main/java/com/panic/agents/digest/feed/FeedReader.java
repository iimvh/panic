package com.panic.agents.digest.feed;

import java.util.List;

/**
 * Reads one feed URL into articles. This is a system boundary (HTTP + third-party XML), so it is the
 * seam tests stub; the domain service depends on this interface rather than on ROME.
 */
public interface FeedReader {

	/**
	 * @throws FeedReadException when the publication is unreachable or returns unparseable content;
	 *                           callers log and skip, so one broken publication never breaks a digest.
	 */
	List<FetchedArticle> read(String feedUrl, String sourceName);
}

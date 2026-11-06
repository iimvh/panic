package com.panic.agents.digest.digest;

import com.panic.agents.digest.feed.NewsItem;
import java.util.List;

/**
 * Turns a set of candidate items into the digest body. This is the model boundary: summarization
 * runs once per day at generation time, and reading a digest never calls it.
 */
public interface DigestSummarizer {

	/**
	 * @param date        the digest date
	 * @param candidates  deduplicated candidate articles (metadata only), freshest first
	 * @param maxItems    upper bound on items the writer may produce
	 * @return intro + sectioned, summarized items
	 */
	DigestSections summarize(java.time.LocalDate date, List<NewsItem> candidates, int maxItems);
}

package com.panic.agents.digest.feed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One publication we ingest. The source list is data, not code: an operator adds or disables a
 * publication by changing this table, never by redeploying.
 */
@Entity
@Table(name = "digest_feed_sources")
@Getter
@Setter
public class FeedSource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(name = "feed_url", nullable = false, unique = true, length = 1024)
	private String feedUrl;

	@Column(nullable = false, length = 64)
	private String category;

	@Column(nullable = false)
	private boolean enabled = true;

	public static FeedSource of(String name, String feedUrl, String category) {
		FeedSource source = new FeedSource();
		source.setName(name);
		source.setFeedUrl(feedUrl);
		source.setCategory(category);
		source.setEnabled(true);
		return source;
	}
}

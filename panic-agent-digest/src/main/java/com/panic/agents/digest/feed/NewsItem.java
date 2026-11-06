package com.panic.agents.digest.feed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A fetched article's <b>metadata only</b> — title, link, short excerpt, publication time, source and
 * category. Full article text is never stored or republished; every item credits and links its source.
 * <p>
 * Uniqueness is enforced by the database per source plus external article id ({@code guid}), with the
 * link as fallback when the feed omits an id — deduplication is a constraint, not a best effort.
 */
@Entity
@Table(name = "digest_news_items",
		uniqueConstraints = @UniqueConstraint(name = "uk_news_item_source_external",
				columnNames = { "source_id", "external_id" }))
@Getter
@Setter
public class NewsItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "source_id", nullable = false)
	private FeedSource source;

	@Column(name = "external_id", nullable = false, length = 512)
	private String externalId;

	@Column(nullable = false, length = 1024)
	private String title;

	@Column(nullable = false, length = 2048)
	private String link;

	@Column(length = 2048)
	private String excerpt;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(nullable = false, length = 64)
	private String category;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt = Instant.now();

	/** Set when this item was chosen into a digest (see {@code digest.Digest}). */
	@Column(name = "digest_id")
	private Long digestId;
}

package com.panic.agents.digest.digest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * One day's digest — a single stored artifact read by both the public page and the chat agent, so
 * the two surfaces never disagree. The unique {@code digestDate} makes "one digest per day" a
 * database guarantee rather than a convention.
 * <p>
 * {@code sectionsJson} holds the sectioned items as JSON: a list of {@code {category, items:[...]}}
 * where each item carries only metadata of its source article.
 */
@Entity
@Table(name = "digests")
@Getter
@Setter
public class Digest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "digest_date", nullable = false, unique = true)
	private LocalDate digestDate;

	@Column(name = "intro_text", columnDefinition = "TEXT")
	private String introText;

	@Column(name = "sections_json", columnDefinition = "TEXT")
	private String sectionsJson;

	@Column(name = "item_count", nullable = false)
	private int itemCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Status status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	public enum Status {
		/** Content chosen and stored; safe to serve. */
		READY,
		/** No items could be produced; recorded so an empty day is distinguishable from a broken run. */
		EMPTY,
		/** Generation failed part-way; the row exists so operators can see the failure. */
		FAILED
	}
}

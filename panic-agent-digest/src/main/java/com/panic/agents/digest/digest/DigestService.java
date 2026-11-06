package com.panic.agents.digest.digest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The single service seam of the digest feature. Both surfaces sit on it: the scheduled morning run
 * calls {@link #generate(LocalDate, boolean)} directly (deterministic), and the agents wrap the same
 * methods for chat. Tests exercise this interface against the real persistence layer, with the
 * {@code FeedReader} and {@code DigestSummarizer} boundaries stubbed.
 */
public interface DigestService {

	/** Fetch every enabled source into news items; returns how many new items were stored. */
	int ingest();

	/**
	 * Produce the digest for {@code date}: ingest, deduplicate across sources, summarize (bounded),
	 * and persist. Idempotent per day — an existing ready digest is returned untouched unless
	 * {@code force} is set. Records {@code FAILED}/{@code EMPTY} status instead of throwing on an
	 * unusable run.
	 */
	Digest generate(LocalDate date, boolean force);

	Optional<Digest> find(LocalDate date);

	/** Today's digest in the configured digest zone, if it exists. */
	Optional<Digest> today();

	boolean hasDigest(LocalDate date);

	/** Available digest dates, newest first. */
	List<LocalDate> availableDates();

	/** Human-readable chat answer for a date, or a plain "not available" message. */
	String answerFor(LocalDate date);
}

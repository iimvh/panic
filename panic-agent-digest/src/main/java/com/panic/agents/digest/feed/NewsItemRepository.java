package com.panic.agents.digest.feed;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsItemRepository extends JpaRepository<NewsItem, Long> {

	boolean existsBySource_IdAndExternalId(Long sourceId, String externalId);

	/** Items whose publication time falls in the window, freshest first. */
	List<NewsItem> findByPublishedAtAfterOrderByPublishedAtDesc(Instant threshold);

	/** Items still unassigned to a digest, freshest first — the candidate pool for generation. */
	List<NewsItem> findByDigestIdIsNullAndPublishedAtAfterOrderByPublishedAtDesc(Instant threshold);

	List<NewsItem> findByDigestIdOrderByIdAsc(Long digestId);
}

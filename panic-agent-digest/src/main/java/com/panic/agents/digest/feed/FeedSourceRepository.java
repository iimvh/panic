package com.panic.agents.digest.feed;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedSourceRepository extends JpaRepository<FeedSource, Long> {

	List<FeedSource> findByEnabledTrue();

	boolean existsByFeedUrl(String feedUrl);
}

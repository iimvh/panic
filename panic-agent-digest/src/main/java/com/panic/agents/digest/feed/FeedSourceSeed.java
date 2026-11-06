package com.panic.agents.digest.feed;

import java.util.List;

/**
 * The built-in source list seeded on first run so the product works out of the box. A supplier rather
 * than a bean of concrete rows keeps the domain service testable without the seed list.
 */
@FunctionalInterface
public interface FeedSourceSeed {

	List<FeedSource> sources();

	FeedSourceSeed EMPTY = List::of;
}

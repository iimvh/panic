package com.panic.agents.digest.feed;

import java.util.List;

/**
 * Seed list seeded on first run. Vietnamese multi-topic publications (technology, economy, society);
 * an operator edits the {@code digest_feed_sources} table afterwards without a redeploy.
 */
public final class BuiltInFeedSources implements FeedSourceSeed {

	private static final List<SourceDefinition> DEFINITIONS = List.of(
			new SourceDefinition("VnExpress – Công nghệ", "https://vnexpress.net/rss/so-hoa.rss", "technology"),
			new SourceDefinition("VnExpress – Kinh doanh", "https://vnexpress.net/rss/kinh-doanh.rss", "economy"),
			new SourceDefinition("VnExpress – Thời sự", "https://vnexpress.net/rss/thoi-su.rss", "society"),
			new SourceDefinition("Tuổi Trẻ – Nhịp sống số", "https://tuoitre.vn/rss/nhip-song-so.rss", "technology"),
			new SourceDefinition("Thanh Niên – Kinh tế", "https://thanhnien.vn/rss/kinh-te.rss", "economy"),
			new SourceDefinition("Thanh Niên – Thời sự", "https://thanhnien.vn/rss/thoi-su.rss", "society"),
			new SourceDefinition("VietNamNet – Công nghệ", "https://vietnamnet.vn/cong-nghe.rss", "technology"),
			new SourceDefinition("VietNamNet – Kinh doanh", "https://vietnamnet.vn/kinh-doanh.rss", "economy"),
			new SourceDefinition("VietNamNet – Thời sự", "https://vietnamnet.vn/thoi-su.rss", "society"));

	public static final BuiltInFeedSources INSTANCE = new BuiltInFeedSources();

	private BuiltInFeedSources() {
	}

	@Override
	public List<FeedSource> sources() {
		return DEFINITIONS.stream()
				.map(definition -> FeedSource.of(definition.name(), definition.url(), definition.category()))
				.toList();
	}

	private record SourceDefinition(String name, String url, String category) {
	}
}

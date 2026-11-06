package com.panic.agents.digest.agent;

import com.panic.agents.digest.digest.DigestService;
import com.panic.agents.digest.feed.FeedSourceRepository;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Chat tools of the {@code newsScout} agent: ingest the configured feeds and report what sources
 * exist. Both are thin adapters over the domain service and the source table.
 */
public class NewsScoutTools {

	private final DigestService digests;
	private final FeedSourceRepository sources;

	public NewsScoutTools(DigestService digests, FeedSourceRepository sources) {
		this.digests = digests;
		this.sources = sources;
	}

	@Tool(description = "Fetch every enabled Vietnamese news feed and store new article metadata. "
			+ "Returns how many new items were stored. Broken feeds are skipped and logged.")
	public String fetch() {
		int stored = digests.ingest();
		return "Đã thu thập " + stored + " tin mới.";
	}

	@Tool(description = "List the configured feed sources (name, category, enabled) so the operator can "
			+ "see what publications the digest draws from.")
	public String listSources() {
		var all = sources.findAll();
		if (all.isEmpty()) {
			return "Chưa cấu hình nguồn tin nào.";
		}
		StringBuilder out = new StringBuilder();
		for (var source : all) {
			out.append("- ").append(source.getName()).append(" [").append(source.getCategory()).append("] ")
					.append(source.getFeedUrl()).append(source.isEnabled() ? "" : " (disabled)").append('\n');
		}
		return out.toString().trim();
	}
}

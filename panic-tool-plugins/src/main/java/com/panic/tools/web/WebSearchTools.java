package com.panic.tools.web;

import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * The model-facing {@code webSearch} tool. Wraps a {@link SearchBackend}; formats hits as a compact
 * numbered list (title, url, publication time, snippet) the model can cite. Fail-open: an empty
 * result or a backend error returns a plain message instead of throwing, so the agent can continue
 * without search.
 */
public class WebSearchTools {

	private final SearchBackend backend;

	public WebSearchTools(SearchBackend backend) {
		this.backend = backend;
	}

	@Tool(name = "webSearch", description = "Search the latest news and the public web. Returns the "
			+ "top results newest first with title, url, publication timestamp and snippet. Use it "
			+ "for current news or recent events; only results carrying a 'Published' line are dated.")
	public String webSearch(
			@ToolParam(description = "Natural-language search query") String query,
			@ToolParam(required = false, description = "Max results (1-10), default 5") Integer limit) {
		if (query == null || query.isBlank()) {
			return "Empty search query.";
		}
		List<SearchResult> results = backend.search(query, limit == null ? 5 : limit);
		if (results.isEmpty()) {
			return "No web results for '" + query + "' (or the search backend is unavailable).";
		}
		StringBuilder out = new StringBuilder("Web results for '").append(query).append("':\n");
		int index = 1;
		for (SearchResult result : results) {
			out.append(index++).append(". ").append(result.title()).append("\n")
					.append("   ").append(result.url()).append("\n");
			if (result.publishedAt() != null && !result.publishedAt().isBlank()) {
				out.append("   Published: ").append(result.publishedAt()).append("\n");
			}
			if (!result.snippet().isBlank()) {
				out.append("   ").append(result.snippet()).append("\n");
			}
		}
		return out.toString().trim();
	}
}

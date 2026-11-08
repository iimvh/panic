package com.panic.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The tool output must carry the publication time so the model can reject stale stories. */
class WebSearchToolsTest {

	@Test
	void formatsThePublishedTimestampWhenTheBackendProvidesOne() {
		SearchBackend stub = new SearchBackend() {
			public String name() {
				return "stub";
			}

			public List<SearchResult> search(String query, int limit) {
				return List.of(new SearchResult("Fresh story", "https://a.vn", "snip",
						"2026-11-08T15:55:00Z"));
			}
		};

		String output = new WebSearchTools(stub).webSearch("tin", 3);

		assertThat(output).contains("Published: 2026-11-08T15:55:00Z");
	}

	@Test
	void omitsTheTimestampWhenTheBackendDoesNotKnowIt() {
		SearchBackend stub = new SearchBackend() {
			public String name() {
				return "stub";
			}

			public List<SearchResult> search(String query, int limit) {
				return List.of(new SearchResult("Undated page", "https://a.vn", "snip"));
			}
		};

		assertThat(new WebSearchTools(stub).webSearch("tin", 3)).doesNotContain("Published:");
	}
}

package com.panic.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline checks for the DuckDuckGo HTML parser and the web-search tool formatting. No network:
 * a captured results fragment drives the parser, and a stub backend drives the tool.
 */
class DuckDuckGoHtmlSearchBackendTest {

	private static final String HTML = """
			<div class="result results_links">
			  <h2 class="result__title">
			    <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fnews%3Fa%3D1&amp;rut=x">
			      Tin <b>mới</b> hôm nay
			    </a>
			  </h2>
			  <a class="result__snippet" href="https://example.com/news">Tóm tắt nội dung &amp; chi tiết.</a>
			</div>
			<div class="result results_links">
			  <h2 class="result__title">
			    <a class="result__a" href="https://www.another.vn/page">Second result</a>
			  </h2>
			  <a class="result__snippet" href="https://www.another.vn/page">Snippet two</a>
			</div>
			""";

	@Test
	void parsesTitlesUrlsAndSnippetsAndUnwrapsRedirects() {
		List<SearchResult> results = DuckDuckGoHtmlSearchBackend.parse(HTML, 5);

		assertThat(results).hasSize(2);
		assertThat(results.get(0).title()).isEqualTo("Tin mới hôm nay");
		assertThat(results.get(0).url()).isEqualTo("https://example.com/news?a=1");
		assertThat(results.get(0).snippet()).isEqualTo("Tóm tắt nội dung & chi tiết.");
		assertThat(results.get(1).url()).isEqualTo("https://www.another.vn/page");
	}

	@Test
	void respectsTheResultLimit() {
		assertThat(DuckDuckGoHtmlSearchBackend.parse(HTML, 1)).hasSize(1);
	}

	@Test
	void blankHtmlYieldsNoResults() {
		assertThat(DuckDuckGoHtmlSearchBackend.parse("", 5)).isEmpty();
		assertThat(DuckDuckGoHtmlSearchBackend.parse(null, 5)).isEmpty();
	}

	@Test
	void toolFormatsResultsAndStaysFailOpenOnEmpty() {
		SearchBackend stub = new SearchBackend() {
			public String name() {
				return "stub";
			}

			public List<SearchResult> search(String query, int limit) {
				return List.of(new SearchResult("A", "https://a.vn", "snip"));
			}
		};
		String withResults = new WebSearchTools(stub).webSearch("tin", 3);
		assertThat(withResults).contains("A").contains("https://a.vn").contains("snip");

		SearchBackend empty = new SearchBackend() {
			public String name() {
				return "empty";
			}

			public List<SearchResult> search(String query, int limit) {
				return List.of();
			}
		};
		assertThat(new WebSearchTools(empty).webSearch("tin", 3)).contains("No web results");
	}
}

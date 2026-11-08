package com.panic.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Offline checks for the Bing HTML parser and result-count/limit behaviour. No network. */
class BingHtmlSearchBackendTest {

	private static final String HTML = """
			<ol id="b_results">
			<li class="b_algo" data-id>
			  <h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=x&amp;u=a1aHR0cHM6Ly93d3cudGVjaHoudm4v&amp;ntb=1">
			    Techz.vn - Tin cong nghe</a></h2>
			  <div class="b_caption"><p class="b_lineclamp2">Tom tat tin cong nghe 24h.</p></div>
			</li>
			<li class="b_algo" data-id>
			  <h2><a href="https://example.com/plain">Second result</a></h2>
			  <div class="b_caption"><p class="b_lineclamp2">Second snippet</p></div>
			</li>
			</ol>
			""";

	@Test
	void parsesTitleUrlAndSnippetAndDecodesBingRedirect() {
		List<SearchResult> results = BingHtmlSearchBackend.parse(HTML, 5);

		assertThat(results).hasSize(2);
		assertThat(results.get(0).title()).isEqualTo("Techz.vn - Tin cong nghe");
		assertThat(results.get(0).url()).isEqualTo("https://www.techz.vn/");
		assertThat(results.get(0).snippet()).isEqualTo("Tom tat tin cong nghe 24h.");
		assertThat(results.get(1).url()).isEqualTo("https://example.com/plain");
	}

	@Test
	void respectsLimit() {
		assertThat(BingHtmlSearchBackend.parse(HTML, 1)).hasSize(1);
	}

	@Test
	void blankHtmlYieldsNoResults() {
		assertThat(BingHtmlSearchBackend.parse("", 5)).isEmpty();
		assertThat(BingHtmlSearchBackend.parse(null, 5)).isEmpty();
	}

	@Test
	void decodesBothRedirectForms() {
		assertThat(BingHtmlSearchBackend.unwrap("https://www.bing.com/ck/a?u=a1aHR0cHM6Ly9hLnZu"))
				.isEqualTo("https://a.vn");
		assertThat(BingHtmlSearchBackend.unwrap("https://example.com/x")).isEqualTo("https://example.com/x");
	}
}

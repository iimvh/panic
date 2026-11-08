package com.panic.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Offline checks for the Google News RSS parser, freshness window and ordering. No network. */
class GoogleNewsRssSearchBackendTest {

	private static final Instant NOW = Instant.parse("2026-11-08T16:00:00Z");

	private static final String XML = """
			<rss version="2.0"><channel>
			<item>
			  <title>Older but fresh story - CNBC</title>
			  <link>https://news.google.com/rss/articles/old?oc=5</link>
			  <pubDate>Sat, 07 Nov 2026 13:00:01 GMT</pubDate>
			  <source url="https://www.cnbc.com">CNBC</source>
			</item>
			<item>
			  <title>Newest &#8211; story - The Verge</title>
			  <link>https://news.google.com/rss/articles/new&amp;oc=5</link>
			  <pubDate>Sun, 08 Nov 2026 15:55:00 GMT</pubDate>
			  <source url="https://www.theverge.com">The Verge</source>
			</item>
			<item>
			  <title>Stone age story - Example</title>
			  <link>https://news.google.com/rss/articles/stale?oc=5</link>
			  <pubDate>Wed, 14 Oct 2026 18:13:00 GMT</pubDate>
			  <source url="https://example.com">Example</source>
			</item>
			<item>
			  <title>Undated evergreen page</title>
			  <link>https://example.com/home</link>
			  <source url="https://example.com">Example</source>
			</item>
			</channel></rss>
			""";

	@Test
	void keepsOnlyDatedItemsInsideTheWindowNewestFirst() {
		List<SearchResult> results = GoogleNewsRssSearchBackend.parse(
				XML, 5, NOW, Duration.ofHours(48));

		assertThat(results).hasSize(2);
		assertThat(results.get(0).title()).isEqualTo("Newest \u2013 story - The Verge");
		assertThat(results.get(0).url()).isEqualTo("https://news.google.com/rss/articles/new&oc=5");
		assertThat(results.get(0).publishedAt()).isEqualTo("2026-11-08T15:55:00Z");
		assertThat(results.get(0).snippet()).isEqualTo("Source: The Verge");
		assertThat(results.get(1).title()).isEqualTo("Older but fresh story - CNBC");
	}

	@Test
	void respectsTheResultLimitAfterSorting() {
		assertThat(GoogleNewsRssSearchBackend.parse(XML, 1, NOW, Duration.ofHours(48)))
				.singleElement()
				.extracting(SearchResult::title)
				.isEqualTo("Newest \u2013 story - The Verge");
	}

	@Test
	void blankFeedYieldsNoResults() {
		assertThat(GoogleNewsRssSearchBackend.parse("", 5, NOW, Duration.ofHours(48))).isEmpty();
		assertThat(GoogleNewsRssSearchBackend.parse(null, 5, NOW, Duration.ofHours(48))).isEmpty();
	}

	@Test
	void addsADailyFreshnessOperatorUnlessTheModelChoseOne() {
		assertThat(GoogleNewsRssSearchBackend.withFreshness("tech news"))
				.isEqualTo("tech news when:1d");
		assertThat(GoogleNewsRssSearchBackend.withFreshness("tech news when:7d"))
				.isEqualTo("tech news when:7d");
	}

	@Test
	void decodesCdataNumericEntitiesAndTags() {
		assertThat(GoogleNewsRssSearchBackend.clean(
				"<![CDATA[<b>Tin</b> m&#7899;i &amp; hay]]>")).isEqualTo("Tin mới & hay");
	}
}

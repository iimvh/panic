package com.panic.agents.digest.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.panic.agents.digest.feed.FeedReadException;
import com.panic.agents.digest.feed.FeedReader;
import com.panic.agents.digest.feed.FeedSource;
import com.panic.agents.digest.feed.FeedSourceRepository;
import com.panic.agents.digest.feed.FetchedArticle;
import com.panic.agents.digest.feed.NewsItem;
import com.panic.agents.digest.feed.NewsItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Primary seam: the digest service against the real persistence layer, with the feed reader and the
 * summarizer (the two system boundaries) stubbed. Asserts observable behavior — what is stored and
 * what a reader gets back — never internal wiring or the clock implementation.
 */
@DataJpaTest
@Import(DigestServiceTest.TestConfig.class)
class DigestServiceTest {

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = "com.panic.agents.digest")
	@org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages = "com.panic.agents.digest")
	static class TestConfig {
	}

	private static final ZoneId DIGEST_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
	private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

	@Autowired
	private FeedSourceRepository sources;

	@Autowired
	private NewsItemRepository items;

	@Autowired
	private DigestRepository digests;

	private StubFeedReader reader;
	private StubSummarizer summarizer;
	private MutableClock clock;
	private DigestService service;

	@BeforeEach
	void setUp() {
		reader = new StubFeedReader();
		summarizer = new StubSummarizer();
		clock = new MutableClock(Instant.parse("2026-11-06T00:30:00Z"), DIGEST_ZONE);
		service = new DefaultDigestService(sources, items, digests,
				() -> List.of(FeedSource.of("VnExpress", "https://vnexpress.example/rss", "society")),
				reader, summarizer, clock, DIGEST_ZONE, 8);
	}

	@Test
	void seedsTheBuiltInSourceListWhenTheTableIsEmpty() {
		service.ingest();

		assertThat(sources.findAll()).extracting(FeedSource::getName).containsExactly("VnExpress");
	}

	@Test
	void storesArticleMetadataAndSkipsDuplicatesPerSource() {
		service.ingest();
		reader.articles("https://vnexpress.example/rss", "VnExpress",
				article("g1", "Tin một", "https://vnexpress.example/1"),
				article("g1", "Tin một (lặp)", "https://vnexpress.example/1"),
				article("g2", "Tin hai", "https://vnexpress.example/2"));

		int stored = service.ingest();

		assertThat(stored).isEqualTo(2);
		assertThat(items.findAll()).extracting(NewsItem::getExternalId).containsExactlyInAnyOrder("g1", "g2");
		NewsItem first = items.findAll().stream().filter(i -> "g1".equals(i.getExternalId())).findFirst().orElseThrow();
		assertThat(first.getTitle()).isEqualTo("Tin một");
		assertThat(first.getCategory()).isEqualTo("society");
		assertThat(first.getSource().getName()).isEqualTo("VnExpress");
	}

	@Test
	void aFailingFeedIsSkippedWhileOtherSourcesStillIngest() {
		service = new DefaultDigestService(sources, items, digests,
				() -> List.of(FeedSource.of("Broken", "https://broken.example/rss", "society"),
						FeedSource.of("VnExpress", "https://vnexpress.example/rss", "society")),
				reader, summarizer, clock, DIGEST_ZONE, 8);
		reader.fail("https://broken.example/rss");
		reader.articles("https://vnexpress.example/rss", "VnExpress",
				article("g1", "Tin một", "https://vnexpress.example/1"));

		int stored = service.ingest();

		assertThat(stored).isEqualTo(1);
	}

	@Test
	void generatesOneDigestPerDayAndDeduplicatesTheSameStoryAcrossSources() {
		service = new DefaultDigestService(sources, items, digests,
				() -> List.of(FeedSource.of("A", "https://a.example/rss", "technology"),
						FeedSource.of("B", "https://b.example/rss", "society")),
				reader, summarizer, clock, DIGEST_ZONE, 8);
		reader.articles("https://a.example/rss", "A",
				article("a1", "AI đang thay đổi ngành", "https://a.example/story?utm_source=x"));
		reader.articles("https://b.example/rss", "B",
				article("b1", "Bản tin khác", "https://b.example/other"),
				article("b2", "AI đang thay đổi ngành", "https://a.example/story"));
		summarizer.echoAll();

		Digest digest = service.generate(DAY, false);

		assertThat(digest.getStatus()).isEqualTo(Digest.Status.READY);
		// The two links canonicalize to the same story, so only one survives generation.
		assertThat(digest.getItemCount()).isEqualTo(2);
		DigestSections body = DigestSections.fromJson(digest.getSectionsJson());
		assertThat(body.itemCount()).isEqualTo(2);
		assertThat(body.sections()).extracting(DigestSections.Section::category)
				.containsExactly("technology", "society");
	}

	@Test
	void regenerationIsANoOpUnlessForced() {
		service.ingest();
		reader.articles("https://vnexpress.example/rss", "VnExpress",
				article("g1", "Tin một", "https://vnexpress.example/1"));
		summarizer.echoAll();
		Digest first = service.generate(DAY, false);
		long firstId = first.getId();

		reader.articles("https://vnexpress.example/rss", "VnExpress",
				article("g2", "Tin hai", "https://vnexpress.example/2"));
		Digest again = service.generate(DAY, false);

		assertThat(again.getId()).isEqualTo(firstId);
		assertThat(again.getItemCount()).isEqualTo(1);

		Digest forced = service.generate(DAY, true);
		assertThat(forced.getId()).isEqualTo(firstId);
		assertThat(forced.getItemCount()).isEqualTo(2);
	}

	@Test
	void anEmptyRunIsRecordedAsEmptyRatherThanLeftAsABlankDay() {
		Digest digest = service.generate(DAY, false);

		assertThat(digest.getStatus()).isEqualTo(Digest.Status.EMPTY);
		assertThat(digest.getItemCount()).isZero();
		assertThat(service.hasDigest(DAY)).isTrue();
	}

	@Test
	void readsTodayInTheConfiguredZone() {
		service.ingest();
		reader.articles("https://vnexpress.example/rss", "VnExpress",
				article("g1", "Tin một", "https://vnexpress.example/1"));
		summarizer.echoAll();
		service.generate(DAY, false);

		assertThat(service.today()).isPresent();
		assertThat(service.today().orElseThrow().getDigestDate()).isEqualTo(DAY);
		clock.set(Instant.parse("2026-11-07T00:30:00Z"));
		assertThat(service.today()).isEmpty();
	}

	@Test
	void answerForReportsAPlainMessageWhenNoDigestExists() {
		String missing = service.answerFor(DAY);

		assertThat(missing).contains("chưa có").doesNotContain("{{\"title");
	}

	@Test
	void answerForRendersTheStoredDigestRatherThanSummarizingAgain() {
		service.ingest();
		reader.articles("https://vnexpress.example/rss", "VnExpress",
				article("g1", "Tin một", "https://vnexpress.example/1"));
		summarizer.echoAll();
		service.generate(DAY, false);
		int callsAfterGenerate = summarizer.calls();

		String answer = service.answerFor(DAY);

		assertThat(summarizer.calls()).isEqualTo(callsAfterGenerate);
		assertThat(answer).contains("Tin một", "https://vnexpress.example/1", "VnExpress");
	}

	@Test
	void archiveListsAvailableDatesNewestFirst() {
		service.generate(DAY, false);
		service.generate(DAY.minusDays(1), false);
		service.generate(DAY.minusDays(2), false);

		assertThat(service.availableDates()).containsExactly(DAY, DAY.minusDays(1), DAY.minusDays(2));
	}

	private static FetchedArticle article(String id, String title, String link) {
		return new FetchedArticle(id, title, link, "Mô tả ngắn của " + title + ".",
				Instant.parse("2026-11-06T00:00:00Z"));
	}

	/** Stubbed feed boundary; returns what the test put in, or fails when told to. */
	static final class StubFeedReader implements FeedReader {

		private final Map<String, List<FetchedArticle>> byUrl = new LinkedHashMap<>();

		void articles(String url, String sourceName, FetchedArticle... articles) {
			byUrl.computeIfAbsent(url, key -> new ArrayList<>()).addAll(List.of(articles));
		}

		void fail(String url) {
			byUrl.put(url, null);
		}

		@Override
		public List<FetchedArticle> read(String feedUrl, String sourceName) {
			if (byUrl.containsKey(feedUrl) && byUrl.get(feedUrl) == null) {
				throw new FeedReadException("boom: " + feedUrl, null);
			}
			return byUrl.getOrDefault(feedUrl, List.of());
		}
	}

	/** Stubbed model boundary: builds sections from the candidates' own metadata. */
	static final class StubSummarizer implements DigestSummarizer {

		private int calls;

		void echoAll() {
		}

		int calls() {
			return calls;
		}

		@Override
		public DigestSections summarize(LocalDate date, List<NewsItem> candidates, int maxItems) {
			calls++;
			List<DigestSections.Item> chosen = candidates.stream()
					.limit(maxItems)
					.map(item -> new DigestSections.Item(item.getTitle(),
							item.getExcerpt() == null ? item.getTitle() : item.getExcerpt(),
							item.getLink(), item.getSource().getName(), item.getCategory()))
					.toList();
			return new DigestSections("Bản tin ngày " + date, DigestSections.groupByCategory(chosen).sections());
		}
	}

	static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now, ZoneId zone) {
			this.now = now;
			this.zone = zone;
		}

		private final ZoneId zone;

		void set(Instant instant) {
			now = instant;
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(now, zone);
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}

package com.panic.agents.digest.digest;

import com.panic.agents.digest.feed.CanonicalLink;
import com.panic.agents.digest.feed.FeedReader;
import com.panic.agents.digest.feed.FeedSource;
import com.panic.agents.digest.feed.FeedSourceRepository;
import com.panic.agents.digest.feed.FeedSourceSeed;
import com.panic.agents.digest.feed.FetchedArticle;
import com.panic.agents.digest.feed.NewsItem;
import com.panic.agents.digest.feed.NewsItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link DigestService}: ingest feeds, choose the day's stories, summarize once, store one
 * digest per date. Both the scheduled run and the chat agents call these methods, so the morning
 * pipeline is deterministic and the two surfaces read the same stored artifact.
 */
public class DefaultDigestService implements DigestService {

	private static final Logger log = LoggerFactory.getLogger(DefaultDigestService.class);

	/** Only articles published within this window are considered "today's news". */
	private static final Duration FRESHNESS = Duration.ofHours(36);

	private final FeedSourceRepository sources;
	private final NewsItemRepository items;
	private final DigestRepository digests;
	private final FeedSourceSeed seed;
	private final FeedReader reader;
	private final DigestSummarizer summarizer;
	private final Clock clock;
	private final ZoneId zone;
	private final int maxItems;

	public DefaultDigestService(FeedSourceRepository sources, NewsItemRepository items, DigestRepository digests,
			FeedSourceSeed seed, FeedReader reader, DigestSummarizer summarizer, Clock clock, ZoneId zone,
			int maxItems) {
		this.sources = sources;
		this.items = items;
		this.digests = digests;
		this.seed = seed;
		this.reader = reader;
		this.summarizer = summarizer;
		this.clock = clock;
		this.zone = zone;
		this.maxItems = maxItems;
	}

	@Override
	@Transactional
	public int ingest() {
		seedIfEmpty();
		int stored = 0;
		for (FeedSource source : sources.findByEnabledTrue()) {
			try {
				stored += ingestSource(source);
			} catch (RuntimeException ex) {
				// A broken publication must never take the product down: warn and continue.
				log.warn("Skipping feed '{}' ({}): {}", source.getName(), source.getFeedUrl(), ex.getMessage());
			}
		}
		return stored;
	}

	private int ingestSource(FeedSource source) {
		List<FetchedArticle> articles = reader.read(source.getFeedUrl(), source.getName());
		Instant fetchedAt = clock.instant();
		int stored = 0;
		for (FetchedArticle article : articles) {
			if (article == null || article.title() == null || article.title().isBlank()
					|| article.link() == null || article.link().isBlank()) {
				continue;
			}
			String externalId = article.identity(article.link());
			if (externalId.isBlank() || items.existsBySource_IdAndExternalId(source.getId(), externalId)) {
				continue;
			}
			NewsItem item = new NewsItem();
			item.setSource(source);
			item.setExternalId(externalId);
			item.setTitle(article.title().trim());
			item.setLink(article.link().trim());
			item.setExcerpt(article.excerpt());
			item.setPublishedAt(article.publishedAt() == null ? fetchedAt : article.publishedAt());
			item.setCategory(source.getCategory());
			item.setFetchedAt(fetchedAt);
			items.save(item);
			stored++;
		}
		return stored;
	}

	@Override
	@Transactional
	public Digest generate(LocalDate date, boolean force) {
		Optional<Digest> existing = findByDate(date);
		if (existing.isPresent()) {
			// A READY day is final: the morning run and chat reads must never rewrite good content.
			// EMPTY/FAILED days are not a no-op: the next run retries them, so a delayed or broken
			// morning can still recover.
			if (!force && existing.get().getStatus() == Digest.Status.READY) {
				return existing.get();
			}
			// Regeneration replaces the body of the same day; the day and its id stay stable.
			return regenerate(existing.get());
		}
		ingest();
		List<NewsItem> candidates = balanceByCategory(deduplicate(
				items.findByDigestIdIsNullAndPublishedAtAfterOrderByPublishedAtDesc(
						date.atStartOfDay(zone).toInstant().minus(FRESHNESS))));
		Digest digest = new Digest();
		digest.setDigestDate(date);
		digest.setCreatedAt(clock.instant());
		if (candidates.isEmpty()) {
			digest.setStatus(Digest.Status.EMPTY);
			digest.setIntroText("Hôm nay chưa có tin mới.");
			digest.setSectionsJson(DigestSections.empty().toJson());
			digest.setItemCount(0);
			return digests.save(digest);
		}
		DigestSections sections = summarizeSafely(date, candidates, digest);
		digest.setSectionsJson(sections.toJson());
		digest.setItemCount(sections.itemCount());
		Digest saved = digests.save(digest);
		markChosen(candidates, sections, saved);
		return saved;
	}

	private Digest regenerate(Digest digest) {
		ingest();
		// Release this day's own items so the forced run can re-choose them, then exclude items
		// already consumed by other days so a forced run never re-publishes a prior day's stories.
		List<NewsItem> pool = items.findByPublishedAtAfterOrderByPublishedAtDesc(
				digest.getDigestDate().atStartOfDay(zone).toInstant().minus(FRESHNESS));
		List<NewsItem> candidates = balanceByCategory(deduplicate(pool.stream()
				.filter(item -> item.getDigestId() == null || item.getDigestId().equals(digest.getId()))
				.toList()));
		DigestSections sections = summarizeSafely(digest.getDigestDate(), candidates, digest);
		digest.setSectionsJson(sections.toJson());
		digest.setItemCount(sections.itemCount());
		digest.setCreatedAt(clock.instant());
		Digest saved = digests.save(digest);
		markChosen(candidates, sections, saved);
		return saved;
	}

	private DigestSections summarizeSafely(LocalDate date, List<NewsItem> candidates, Digest digest) {
		try {
			DigestSections sections = summarizer.summarize(date, candidates, maxItems);
			digest.setStatus(sections.itemCount() == 0 ? Digest.Status.EMPTY : Digest.Status.READY);
			digest.setIntroText(sections.intro());
			return sections;
		} catch (RuntimeException ex) {
			log.warn("Summarizing the digest for {} failed: {}", date, ex.getMessage());
			digest.setStatus(Digest.Status.FAILED);
			digest.setIntroText("");
			return DigestSections.empty();
		}
	}

	/**
	 * Link exactly the items that made it into the digest so they are not reused tomorrow. Matched
	 * by canonical link; anything the writer returned that does not map back to a candidate is
	 * ignored rather than guessed, so an unconsumed story is never silently dropped from future days.
	 */
	private void markChosen(List<NewsItem> candidates, DigestSections sections, Digest digest) {
		if (digest.getStatus() != Digest.Status.READY) {
			return;
		}
		Set<String> chosenLinks = new LinkedHashSet<>();
		sections.sections().forEach(section -> section.items()
				.forEach(item -> chosenLinks.add(CanonicalLink.of(item.link()))));
		Set<Long> linked = new LinkedHashSet<>();
		for (NewsItem item : candidates) {
			if (chosenLinks.contains(CanonicalLink.of(item.getLink())) && linked.add(item.getId())) {
				item.setDigestId(digest.getId());
				items.save(item);
			}
		}
	}

	/**
	 * Interleave the newest items of each category round-robin, so a single prolific feed cannot
	 * crowd every other topic out of the bounded pool the writer sees. Preserves freshness within a
	 * category.
	 */
	private static List<NewsItem> balanceByCategory(List<NewsItem> items) {
		java.util.Map<String, java.util.ArrayDeque<NewsItem>> byCategory = new java.util.LinkedHashMap<>();
		for (NewsItem item : items) {
			byCategory.computeIfAbsent(item.getCategory(), key -> new java.util.ArrayDeque<>()).add(item);
		}
		List<NewsItem> balanced = new java.util.ArrayList<>(items.size());
		boolean added = true;
		while (added) {
			added = false;
			for (java.util.ArrayDeque<NewsItem> queue : byCategory.values()) {
				NewsItem next = queue.poll();
				if (next != null) {
					balanced.add(next);
					added = true;
				}
			}
		}
		return balanced;
	}

	private List<NewsItem> deduplicate(List<NewsItem> candidates) {
		Set<String> seen = new LinkedHashSet<>();
		return candidates.stream()
				.filter(item -> seen.add(CanonicalLink.of(item.getLink())))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Digest> find(LocalDate date) {
		return date == null ? Optional.empty() : findByDate(date);
	}

	private Optional<Digest> findByDate(LocalDate date) {
		return digests.findByDigestDate(date);
	}

	@Override
	public Optional<Digest> today() {
		return find(LocalDate.now(clock.withZone(zone)));
	}

	@Override
	public boolean hasDigest(LocalDate date) {
		return find(date).isPresent();
	}

	@Override
	public List<LocalDate> availableDates() {
		return digests.findAllByOrderByDigestDateDesc().stream()
				.map(Digest::getDigestDate)
				.toList();
	}

	@Override
	@Transactional
	public List<NewsItem> latestHeadlines(int limit) {
		ingest();
		Instant threshold = clock.instant().minus(FRESHNESS);
		List<NewsItem> recent = deduplicate(items.findByDigestIdIsNullAndPublishedAtAfterOrderByPublishedAtDesc(threshold));
		return balanceByCategory(recent).stream()
				.limit(Math.max(limit, 1))
				.toList();
	}

	@Override
	public String answerFor(LocalDate date) {
		Optional<Digest> digest = find(date);
		if (digest.isEmpty()) {
			return "Ngày " + date + " chưa có bản tin. Bản tin được tạo vào mỗi buổi sáng; "
					+ "bạn có thể quay lại sau hoặc xem bản tin mới nhất tại /ban-tin.";
		}
		Digest stored = digest.get();
		if (stored.getStatus() != Digest.Status.READY) {
			return "Bản tin ngày " + date + " chưa có nội dung (" + stored.getStatus().name().toLowerCase() + ").";
		}
		StringBuilder answer = new StringBuilder();
		if (stored.getIntroText() != null && !stored.getIntroText().isBlank()) {
			answer.append(stored.getIntroText()).append("\n\n");
		}
		DigestSections body = DigestSections.fromJson(stored.getSectionsJson());
		for (DigestSections.Section section : body.sections()) {
			answer.append("## ").append(section.category()).append('\n');
			for (DigestSections.Item item : section.items()) {
				answer.append("- ").append(item.title()).append(" (").append(item.sourceName()).append("): ")
						.append(item.link()).append('\n');
				if (item.summary() != null && !item.summary().isBlank()) {
					answer.append("  ").append(item.summary()).append('\n');
				}
			}
			answer.append('\n');
		}
		answer.append("Đọc bản tin đầy đủ tại /ban-tin/").append(date).append('.');
		return answer.toString().trim();
	}

	private void seedIfEmpty() {
		if (sources.count() > 0) {
			return;
		}
		for (FeedSource source : seed.sources()) {
			if (!sources.existsByFeedUrl(source.getFeedUrl())) {
				sources.save(source);
			}
		}
	}
}

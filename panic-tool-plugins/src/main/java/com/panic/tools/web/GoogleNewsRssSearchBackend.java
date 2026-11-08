package com.panic.tools.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Key-free news search over Google News' RSS endpoint. Unlike scraping a web results page, every
 * item carries a real publication timestamp and publisher, and the query is restricted to the last
 * day ({@code when:1d}), so the digest reports today's news instead of evergreen landing pages.
 * <p>
 * Items without a parseable {@code pubDate} are dropped: a news item the model cannot date is
 * exactly what made the digest recycle "stone age" stories. Parsing is tolerant — a markup change
 * yields fewer results rather than an exception.
 */
public final class GoogleNewsRssSearchBackend implements SearchBackend {

	private static final Logger log = LoggerFactory.getLogger(GoogleNewsRssSearchBackend.class);
	private static final String ENDPOINT = "https://news.google.com/rss/search";
	private static final String USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
					+ "Chrome/124.0 Safari/537.36";

	private static final Pattern ITEM = Pattern.compile("(?s)<item>(.*?)</item>");
	private static final Pattern TITLE = Pattern.compile("(?s)<title>(.*?)</title>");
	private static final Pattern LINK = Pattern.compile("(?s)<link>(.*?)</link>");
	private static final Pattern PUB_DATE = Pattern.compile("(?s)<pubDate>(.*?)</pubDate>");
	private static final Pattern SOURCE = Pattern.compile("(?s)<source[^>]*>(.*?)</source>");
	private static final Pattern TAG = Pattern.compile("<[^>]+>");
	private static final Pattern CDATA = Pattern.compile("(?s)<!\\[CDATA\\[(.*?)]]>");
	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

	private final HttpClient http;
	private final String locale;
	private final Duration maxAge;
	private final Clock clock;

	public GoogleNewsRssSearchBackend() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL).build(), "en-US", Duration.ofHours(48),
				Clock.systemUTC());
	}

	GoogleNewsRssSearchBackend(HttpClient http, String locale, Duration maxAge, Clock clock) {
		this.http = http;
		this.locale = locale == null || locale.isBlank() ? "en-US" : locale;
		this.maxAge = maxAge == null ? Duration.ofHours(48) : maxAge;
		this.clock = clock == null ? Clock.systemUTC() : clock;
	}

	@Override
	public String name() {
		return "google-news-rss";
	}

	@Override
	public List<SearchResult> search(String query, int limit) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		int max = limit <= 0 ? 5 : Math.min(limit, 10);
		HttpRequest request = HttpRequest.newBuilder()
				.uri(uri(query))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", USER_AGENT)
				.header("Accept", "application/rss+xml,application/xml;q=0.9,*/*;q=0.8")
				.header("Accept-Language", locale + ",en;q=0.8")
				.GET()
				.build();
		try {
			HttpResponse<String> response = http.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() != 200) {
				log.warn("Google News answered HTTP {}", response.statusCode());
				return List.of();
			}
			return parse(response.body(), max, clock.instant(), maxAge);
		} catch (Exception ex) {
			log.warn("Google News search failed: {}", ex.getMessage());
			return List.of();
		}
	}

	/** Adds Google's freshness operator unless the model already chose a window. */
	static String withFreshness(String query) {
		if (query.toLowerCase().contains("when:")) {
			return query;
		}
		return query.trim() + " when:1d";
	}

	private URI uri(String query) {
		String[] parts = locale.split("-");
		String language = parts[0];
		String country = parts.length > 1 ? parts[1] : parts[0].toUpperCase();
		return URI.create(ENDPOINT + "?q=" + encode(withFreshness(query))
				+ "&hl=" + encode(language) + "&gl=" + encode(country)
				+ "&ceid=" + encode(country + ":" + language));
	}

	/**
	 * Parses the RSS feed, keeps only dated items no older than {@code maxAge}, and returns the
	 * newest first. Exposed for tests; never touches the network.
	 */
	static List<SearchResult> parse(String xml, int max, Instant now, Duration maxAge) {
		List<SearchResult> results = new ArrayList<>();
		if (xml == null || xml.isBlank()) {
			return results;
		}
		Instant oldest = now.minus(maxAge);
		Matcher items = ITEM.matcher(xml);
		while (items.find()) {
			String item = items.group(1);
			String title = clean(text(item, TITLE));
			String link = clean(text(item, LINK));
			Instant published = parseDate(clean(text(item, PUB_DATE)));
			if (title.isBlank() || link.isBlank() || published == null
					|| published.isBefore(oldest) || published.isAfter(now.plus(Duration.ofHours(1)))) {
				continue;
			}
			String source = clean(text(item, SOURCE));
			String snippet = source.isBlank() ? "" : "Source: " + source;
			results.add(new SearchResult(title, link, snippet, published.toString()));
		}
		results.sort(Comparator.comparing((SearchResult result) -> result.publishedAt()).reversed());
		return results.size() > max ? results.subList(0, max) : results;
	}

	private static String text(String item, Pattern pattern) {
		Matcher matcher = pattern.matcher(item);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static Instant parseDate(String value) {
		if (value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** Strips CDATA/tags, decodes entities, collapses whitespace. */
	static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		Matcher cdata = CDATA.matcher(raw);
		String text = cdata.find() ? cdata.group(1) : raw;
		text = TAG.matcher(text).replaceAll("");
		text = decodeEntities(text);
		return text.replaceAll("\\s+", " ").trim();
	}

	private static String decodeEntities(String text) {
		String decoded = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
				.replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ");
		Matcher numeric = NUMERIC_ENTITY.matcher(decoded);
		StringBuilder out = new StringBuilder(decoded.length());
		while (numeric.find()) {
			int codePoint = Integer.parseInt(numeric.group(2), numeric.group(1).isEmpty() ? 10 : 16);
			numeric.appendReplacement(out, Matcher.quoteReplacement(
					new String(Character.toChars(codePoint))));
		}
		numeric.appendTail(out);
		return out.toString();
	}

	private static String encode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}

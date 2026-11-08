package com.panic.tools.web;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zero-config web search against DuckDuckGo's HTML endpoint ({@code html.duckduckgo.com/html/}).
 * No API key and no extra service: it posts the query and parses the results page.
 * <p>
 * DuckDuckGo wraps every destination in a {@code /l/?uddg=<url-encoded>} redirect; the real URL is
 * unwrapped so the model gets a link it can cite. Parsing is deliberately tolerant: a markup change
 * yields fewer results rather than an exception (the plugin stays fail-open).
 */
public final class DuckDuckGoHtmlSearchBackend implements SearchBackend {

	private static final Logger log = LoggerFactory.getLogger(DuckDuckGoHtmlSearchBackend.class);
	private static final String ENDPOINT = "https://html.duckduckgo.com/html/";
	private static final String USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
					+ "Chrome/124.0 Safari/537.36";

	/** One result anchor: href + visible title. */
	private static final Pattern RESULT_LINK = Pattern.compile(
			"<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	/** The snippet text that follows a result. */
	private static final Pattern SNIPPET = Pattern.compile(
			"<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern TAG = Pattern.compile("<[^>]+>");
	private static final Pattern UDDG = Pattern.compile("[?&]uddg=([^&]+)");

	private final HttpClient http;

	public DuckDuckGoHtmlSearchBackend() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
	}

	DuckDuckGoHtmlSearchBackend(HttpClient http) {
		this.http = http;
	}

	@Override
	public String name() {
		return "duckduckgo-html";
	}

	@Override
	public List<SearchResult> search(String query, int limit) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		int max = limit <= 0 ? 5 : Math.min(limit, 10);
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(ENDPOINT))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", USER_AGENT)
				.header("Accept-Language", "vi,en;q=0.8")
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("q=" + encode(query), StandardCharsets.UTF_8))
				.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() != 200) {
				log.warn("DuckDuckGo answered HTTP {}", response.statusCode());
				return List.of();
			}
			return parse(response.body(), max);
		} catch (Exception ex) {
			// Fail-open: the tool reports the failure; the agent answers without search.
			log.warn("DuckDuckGo search failed: {}", ex.getMessage());
			return List.of();
		}
	}

	/** Parses the results page; exposed for tests. */
	static List<SearchResult> parse(String html, int max) {
		List<SearchResult> results = new ArrayList<>();
		if (html == null || html.isBlank()) {
			return results;
		}
		Matcher snippets = SNIPPET.matcher(html);
		List<String> snippetTexts = new ArrayList<>();
		while (snippets.find()) {
			snippetTexts.add(clean(snippets.group(1)));
		}
		Matcher links = RESULT_LINK.matcher(html);
		int index = 0;
		while (links.find() && results.size() < max) {
			String url = unwrap(links.group(1));
			String title = clean(links.group(2));
			if (url.isBlank() || title.isBlank()) {
				index++;
				continue;
			}
			String snippetText = index < snippetTexts.size() ? snippetTexts.get(index) : "";
			results.add(new SearchResult(title, url, snippetText));
			index++;
		}
		return results;
	}

	/** Unwraps {@code //duckduckgo.com/l/?uddg=<encoded>} to the real destination. */
	static String unwrap(String href) {
		if (href == null || href.isBlank()) {
			return "";
		}
		Matcher matcher = UDDG.matcher(href);
		if (matcher.find()) {
			try {
				return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
			} catch (IllegalArgumentException ex) {
				return matcher.group(1);
			}
		}
		if (href.startsWith("//")) {
			return "https:" + href;
		}
		return href;
	}

	/** Strips tags, decodes the common entities, collapses whitespace. */
	static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		String text = TAG.matcher(raw).replaceAll("");
		text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
				.replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
				.replace("&nbsp;", " ");
		return text.replaceAll("\\s+", " ").trim();
	}

	private static String encode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}

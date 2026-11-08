package com.panic.tools.web;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zero-config web search against Bing's HTML results page. No API key and no extra service.
 * <p>
 * Bing wraps every destination in {@code /ck/a?...&u=a1<base64url>} (or plain {@code &u=<url>}); the
 * real URL is decoded so the model gets a link it can cite. Parsing is tolerant — a markup change
 * yields fewer results rather than an exception, so the plugin stays fail-open.
 */
public final class BingHtmlSearchBackend implements SearchBackend {

	private static final Logger log = LoggerFactory.getLogger(BingHtmlSearchBackend.class);
	private static final String ENDPOINT = "https://www.bing.com/search?q=";
	private static final String USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
					+ "Chrome/124.0 Safari/537.36";

	/** One organic result: each {@code <li class="b_algo">} block. */
	private static final Pattern RESULT_BLOCK = Pattern.compile(
			"(?s)<li class=\"b_algo\".*?(?=<li class=\"b_algo\"|</ol>)", Pattern.CASE_INSENSITIVE);
	/** The title anchor inside a block: its href and visible text. */
	private static final Pattern BLOCK_LINK = Pattern.compile(
			"(?s)<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE);
	/** The snippet paragraph inside a block. */
	private static final Pattern BLOCK_SNIPPET = Pattern.compile(
			"(?s)<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE);
	private static final Pattern U_PARAM = Pattern.compile("[?&]u=([^&]+)");
	private static final Pattern TAG = Pattern.compile("<[^>]+>");

	private final HttpClient http;

	public BingHtmlSearchBackend() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL).build());
	}

	BingHtmlSearchBackend(HttpClient http) {
		this.http = http;
	}

	@Override
	public String name() {
		return "bing-html";
	}

	@Override
	public List<SearchResult> search(String query, int limit) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		int max = limit <= 0 ? 5 : Math.min(limit, 10);
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(ENDPOINT + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", USER_AGENT)
				.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
				.header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
				.GET()
				.build();
		try {
			HttpResponse<String> response = http.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() != 200) {
				log.warn("Bing answered HTTP {}", response.statusCode());
				return List.of();
			}
			return parse(response.body(), max);
		} catch (Exception ex) {
			log.warn("Bing search failed: {}", ex.getMessage());
			return List.of();
		}
	}

	/** Parses organic results from a Bing HTML page; exposed for tests. */
	static List<SearchResult> parse(String html, int max) {
		List<SearchResult> results = new ArrayList<>();
		if (html == null || html.isBlank()) {
			return results;
		}
		Matcher blocks = RESULT_BLOCK.matcher(html);
		while (blocks.find() && results.size() < max) {
			String block = blocks.group();
			Matcher link = BLOCK_LINK.matcher(block);
			if (!link.find()) {
				continue;
			}
			String url = unwrap(decodeEntities(link.group(1)));
			String title = clean(link.group(2));
			if (url.isBlank() || title.isBlank()) {
				continue;
			}
			Matcher snippet = BLOCK_SNIPPET.matcher(block);
			String text = snippet.find() ? clean(snippet.group(1)) : "";
			results.add(new SearchResult(title, url, text));
		}
		return results;
	}

	/** Decodes Bing's {@code /ck/a?...&u=a1<base64url>} (or {@code &u=<url-encoded>}) redirect. */
	static String unwrap(String href) {
		if (href == null || href.isBlank()) {
			return "";
		}
		Matcher matcher = U_PARAM.matcher(href);
		if (matcher.find()) {
			String raw = matcher.group(1);
			if (raw.startsWith("a1")) {
				String decoded = decodeBase64Url(raw.substring(2));
				if (!decoded.isBlank()) {
					return decoded;
				}
			}
			try {
				return URLDecoder.decode(raw, StandardCharsets.UTF_8);
			} catch (IllegalArgumentException ex) {
				return raw;
			}
		}
		if (href.startsWith("//")) {
			return "https:" + href;
		}
		return href;
	}

	private static String decodeBase64Url(String value) {
		try {
			String padded = value + "=".repeat((4 - value.length() % 4) % 4);
			byte[] bytes = Base64.getUrlDecoder().decode(padded);
			return new String(bytes, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			return "";
		}
	}

	/** Strips tags, decodes the common entities, collapses whitespace. */
	static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		return decodeEntities(TAG.matcher(raw).replaceAll("")).replaceAll("\\s+", " ").trim();
	}

	private static String decodeEntities(String text) {
		return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
				.replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
				.replace("&nbsp;", " ");
	}
}

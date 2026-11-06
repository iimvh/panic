package com.panic.agents.digest.feed;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads RSS / Atom / RDF feeds over HTTP with ROME (one parser for all three dialects) rather than
 * hand-rolled XML. A browser-like user agent is used because some Vietnamese publications reject
 * the default client; every failure surfaces as {@link FeedReadException} so the caller can skip
 * the publication and keep going.
 * <p>
 * Only metadata is read: title, link, a short excerpt and the publication time. Article bodies are
 * never fetched.
 */
public class RomeFeedReader implements FeedReader {

	private static final Logger log = LoggerFactory.getLogger(RomeFeedReader.class);
	private static final String USER_AGENT =
			"Mozilla/5.0 (compatible; PanicDigest/1.0; +https://github.com/iimvh/agent-panic)";
	private static final int EXCERPT_LIMIT = 280;

	private final HttpClient http;
	private final Duration timeout;

	public RomeFeedReader(HttpClient http, Duration timeout) {
		this.http = http;
		this.timeout = timeout;
	}

	@Override
	public List<FetchedArticle> read(String feedUrl, String sourceName) {
		String xml = download(feedUrl);
		try (XmlReader reader = new XmlReader(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
			SyndFeed feed = new SyndFeedInput().build(reader);
			List<FetchedArticle> articles = new ArrayList<>();
			for (SyndEntry entry : feed.getEntries()) {
				articles.add(toArticle(entry));
			}
			return articles;
		} catch (Exception ex) {
			throw new FeedReadException("Cannot parse feed '" + sourceName + "' (" + feedUrl + "): "
					+ ex.getMessage(), ex);
		}
	}

	private String download(String feedUrl) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(feedUrl))
					.header("User-Agent", USER_AGENT)
					.header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
					.timeout(timeout)
					.GET()
					.build();
			HttpResponse<String> response = http.send(request,
					HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
			if (response.statusCode() / 100 != 2) {
				throw new FeedReadException("Feed '" + feedUrl + "' returned HTTP " + response.statusCode(), null);
			}
			return response.body();
		} catch (FeedReadException ex) {
			throw ex;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new FeedReadException("Interrupted while reading '" + feedUrl + "'", ex);
		} catch (Exception ex) {
			throw new FeedReadException("Cannot fetch feed '" + feedUrl + "': " + ex.getMessage(), ex);
		}
	}

	private FetchedArticle toArticle(SyndEntry entry) {
		String excerpt = excerptOf(entry);
		Instant published = entry.getPublishedDate() != null ? entry.getPublishedDate().toInstant()
				: entry.getUpdatedDate() != null ? entry.getUpdatedDate().toInstant() : Instant.now();
		return new FetchedArticle(entry.getUri(), entry.getTitle(), entry.getLink(), excerpt, published);
	}

	private String excerptOf(SyndEntry entry) {
		String raw = entry.getDescription() != null ? entry.getDescription().getValue() : null;
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String text = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
		return text.length() <= EXCERPT_LIMIT ? text : text.substring(0, EXCERPT_LIMIT).trim() + "…";
	}
}

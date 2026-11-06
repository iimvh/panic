package com.panic.agents.digest.digest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panic.agents.digest.feed.NewsItem;
import com.panic.core.agent.runtime.AgentApiKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Model-backed {@link DigestSummarizer}: one Vietnamese editorial pass over the day's candidates,
 * producing an intro and a sectioned list of summarized items authored in natural Vietnamese.
 * <p>
 * The model receives <b>metadata only</b> (title, excerpt, source, category, link) and must return
 * links unchanged, so every item keeps crediting and linking its original publication. Costs are
 * bounded because this runs once per day, never on a page or chat read.
 */
public class VietnameseDigestWriter implements DigestSummarizer {

	private static final Logger log = LoggerFactory.getLogger(VietnameseDigestWriter.class);
	private static final ObjectMapper JSON = new ObjectMapper();

	private final ChatClient.Builder chatClientBuilder;
	private final DigestWriterPrompt prompt = new DigestWriterPrompt();
	private final boolean aiEnabled;

	public VietnameseDigestWriter(ChatClient.Builder chatClientBuilder, String apiKey) {
		this.chatClientBuilder = chatClientBuilder;
		this.aiEnabled = AgentApiKey.isConfigured(apiKey);
	}

	@Override
	public DigestSections summarize(LocalDate date, List<NewsItem> candidates, int maxItems) {
		if (!aiEnabled) {
			throw new IllegalStateException("Summarization needs OPENROUTER_API_KEY");
		}
		if (candidates.isEmpty()) {
			return DigestSections.empty();
		}
		String reply = chatClientBuilder.clone().build()
				.prompt()
				.system(prompt.system())
				.user(prompt.user(date, maxItems, candidates.size(), toJson(payload(candidates))))
				.call()
				.content();
		return parse(reply, candidates);
	}

	private List<Map<String, String>> payload(List<NewsItem> candidates) {
		List<Map<String, String>> payload = new ArrayList<>();
		for (NewsItem item : candidates) {
			Map<String, String> entry = new LinkedHashMap<>();
			entry.put("title", item.getTitle());
			entry.put("excerpt", item.getExcerpt() == null ? "" : item.getExcerpt());
			entry.put("source", item.getSource().getName());
			entry.put("category", item.getCategory());
			entry.put("link", item.getLink());
			payload.add(entry);
		}
		return payload;
	}

	/**
	 * Parses the model's JSON. Items keep the source-assigned category and source name from the
	 * candidate with the same link, so the model cannot mis-credit a publication.
	 */
	DigestSections parse(String reply, List<NewsItem> candidates) {
		if (reply == null || reply.isBlank()) {
			throw new IllegalStateException("The writer returned an empty response");
		}
		String json = extractJson(reply);
		RawDigest raw;
		try {
			raw = JSON.readValue(json, new TypeReference<RawDigest>() { });
		} catch (Exception ex) {
			throw new IllegalStateException("The writer returned malformed JSON: " + ex.getMessage(), ex);
		}
		Map<String, NewsItem> byLink = new LinkedHashMap<>();
		candidates.forEach(item -> byLink.putIfAbsent(item.getLink(), item));
		List<DigestSections.Item> items = new ArrayList<>();
		if (raw.items() != null) {
			for (RawItem item : raw.items()) {
				NewsItem source = byLink.get(item.link());
				if (source == null) {
					continue;
				}
				items.add(new DigestSections.Item(item.title(), item.summary(), source.getLink(),
						source.getSource().getName(), source.getCategory()));
			}
		}
		String intro = raw.intro() == null ? "" : raw.intro().trim();
		return new DigestSections(intro, DigestSections.groupByCategory(items).sections());
	}

	private static String extractJson(String reply) {
		int start = reply.indexOf('{');
		int end = reply.lastIndexOf('}');
		if (start < 0 || end <= start) {
			throw new IllegalStateException("No JSON object in the writer response");
		}
		return reply.substring(start, end + 1);
	}

	private static String toJson(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception ex) {
			throw new IllegalStateException("Cannot encode the writer prompt: " + ex.getMessage(), ex);
		}
	}

	private record RawDigest(String intro, List<RawItem> items) {
	}

	private record RawItem(String title, String summary, String link) {
	}
}

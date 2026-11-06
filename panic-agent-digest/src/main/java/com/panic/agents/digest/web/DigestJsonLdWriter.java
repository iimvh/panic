package com.panic.agents.digest.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panic.agents.digest.digest.Digest;
import com.panic.agents.digest.digest.DigestSections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * JSON-LD for a digest page, so search engines understand it as a dated news collection whose items
 * carry or link to their original sources.
 */
@Component
public class DigestJsonLdWriter {

	private static final ObjectMapper JSON = new ObjectMapper();

	public String write(Digest digest, DigestSections body) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("@context", "https://schema.org");
		root.put("@type", "CollectionPage");
		root.put("name", "Bản tin mỗi sáng ngày " + digest.getDigestDate());
		root.put("datePublished", digest.getDigestDate().toString());
		root.put("description", body.intro());
		List<Map<String, Object>> items = new ArrayList<>();
		for (DigestSections.Section section : body.sections()) {
			for (DigestSections.Item item : section.items()) {
				Map<String, Object> node = new LinkedHashMap<>();
				node.put("@type", "NewsArticle");
				node.put("headline", item.title());
				node.put("url", item.link());
				Map<String, Object> publisher = Map.of("@type", "Organization", "name", item.sourceName());
				node.put("publisher", publisher);
				items.add(node);
			}
		}
		root.put("hasPart", items);
		try {
			return JSON.writeValueAsString(root);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Cannot encode digest JSON-LD: " + ex.getMessage(), ex);
		}
	}
}

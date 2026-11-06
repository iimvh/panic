package com.panic.agents.digest.digest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * The body of a digest: an intro paragraph plus topics (categories), each holding its summarized
 * items. Stored as JSON on {@link Digest}; this type is the one shape the page and the chat prompt
 * both read.
 */
public record DigestSections(String intro, List<Section> sections) {

	private static final ObjectMapper JSON = new ObjectMapper();

	public DigestSections {
		intro = intro == null ? "" : intro;
		sections = sections == null ? List.of() : List.copyOf(sections);
	}

	public static DigestSections empty() {
		return new DigestSections("", List.of());
	}

	public int itemCount() {
		return sections.stream().mapToInt(section -> section.items().size()).sum();
	}

	public String toJson() {
		try {
			return JSON.writeValueAsString(this);
		} catch (Exception ex) {
			throw new IllegalStateException("Cannot serialize digest sections: " + ex.getMessage(), ex);
		}
	}

	public static DigestSections fromJson(String json) {
		if (json == null || json.isBlank()) {
			return empty();
		}
		try {
			return JSON.readValue(json, new TypeReference<DigestSections>() { });
		} catch (Exception ex) {
			throw new IllegalStateException("Cannot read digest sections: " + ex.getMessage(), ex);
		}
	}

	/** Section heading plus the items under it. */
	public record Section(String category, List<Item> items) {

		public Section {
			items = items == null ? List.of() : List.copyOf(items);
		}
	}

	/**
	 * One summarized item, linking and crediting its source. Metadata only: the digest never
	 * republishes an article's full text.
	 */
	public record Item(String title, String summary, String link, String sourceName, String category) {
	}

	/** Group items into sections preserving first-seen category order, dropping empty categories. */
	public static DigestSections groupByCategory(List<Item> items) {
		List<Section> sections = new ArrayList<>();
		for (Item item : items) {
			Section existing = sections.stream()
					.filter(section -> section.category().equals(item.category()))
					.findFirst()
					.orElse(null);
			if (existing == null) {
				List<Item> grouped = new ArrayList<>();
				grouped.add(item);
				sections.add(new Section(item.category(), grouped));
			} else {
				List<Item> grouped = new ArrayList<>(existing.items());
				grouped.add(item);
				sections.set(sections.indexOf(existing), new Section(existing.category(), grouped));
			}
		}
		return new DigestSections("", sections);
	}
}

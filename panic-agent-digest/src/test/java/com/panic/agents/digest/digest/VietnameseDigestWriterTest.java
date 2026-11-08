package com.panic.agents.digest.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.panic.agents.digest.feed.FeedSource;
import com.panic.agents.digest.feed.NewsItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The model boundary: what the writer returns must be Vietnamese prose grouped by topic, with each
 * item keeping its original link and its source's own credit — the model cannot mis-credit a
 * publication or drop attribution.
 */
class VietnameseDigestWriterTest {

	private final VietnameseDigestWriter writer = new VietnameseDigestWriter(
			com.panic.core.agent.chat.provider.LlmProviderChatFactory.fixed(
					org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.Builder.class)));

	private final List<NewsItem> candidates = List.of(
			item("AI Việt Nam", "https://vnexpress.example/1", "technology", "VnExpress"),
			item("Giá vàng", "https://tuoitre.example/2", "economy", "Tuổi Trẻ"));

	@Test
	void keepsEachItemsOriginalLinkAndSourceCredit() {
		String reply = """
				```json
				{"intro":"Chào buổi sáng.","items":[
				  {"title":"AI bùng nổ","summary":"Tóm tắt một.","link":"https://vnexpress.example/1"},
				  {"title":"Giá vàng tăng","summary":"Tóm tắt hai.","link":"https://tuoitre.example/2"}
				]}
				```
				""";

		DigestSections sections = writer.parse(reply, candidates);

		assertThat(sections.intro()).isEqualTo("Chào buổi sáng.");
		assertThat(sections.sections()).extracting(DigestSections.Section::category)
				.containsExactly("technology", "economy");
		assertThat(sections.sections().get(0).items().get(0).sourceName()).isEqualTo("VnExpress");
		assertThat(sections.sections().get(1).items().get(0).sourceName()).isEqualTo("Tuổi Trẻ");
		assertThat(sections.sections().get(1).items().get(0).link()).isEqualTo("https://tuoitre.example/2");
	}

	@Test
	void dropsItemsTheModelInventedThatAreNotInTheCandidateSet() {
		String reply = """
				{"intro":"x","items":[{"title":"Bịa","summary":"...","link":"https://invented.example/9"}]}
				""";

		DigestSections sections = writer.parse(reply, candidates);

		assertThat(sections.itemCount()).isZero();
	}

	@Test
	void boundsTheCandidatePoolSoTheReasoningModelHasRoomToAnswer() {
		List<NewsItem> many = java.util.stream.IntStream.range(0, 100)
				.mapToObj(i -> item("Tin " + i, "https://a.example/" + i, "society", "A")).toList();

		assertThat(VietnameseDigestWriter.bound(many, 8)).hasSize(24);
		assertThat(VietnameseDigestWriter.bound(many, 8)).containsExactlyElementsOf(many.subList(0, 24));
	}

	@Test
	void malformedJsonFailsLoudlySoTheDayIsRecordedAsFailed() {
		assertThatThrownBy(() -> writer.parse("xin chào, không có json", candidates))
				.isInstanceOf(IllegalStateException.class);
	}

	private static NewsItem item(String title, String link, String category, String sourceName) {
		NewsItem item = new NewsItem();
		item.setSource(FeedSource.of(sourceName, "https://" + sourceName + "/rss", category));
		item.setExternalId(link);
		item.setTitle(title);
		item.setLink(link);
		item.setExcerpt("trích đoạn");
		item.setCategory(category);
		item.setPublishedAt(Instant.parse("2026-11-06T00:00:00Z"));
		return item;
	}
}

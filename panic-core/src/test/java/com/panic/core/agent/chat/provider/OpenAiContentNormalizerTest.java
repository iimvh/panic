package com.panic.core.agent.chat.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Offline checks: the normalizer turns array content into a string and leaves other shapes alone. */
class OpenAiContentNormalizerTest {

	@Test
	void joinsArrayTextPartsIntoAString() {
		String body = """
				{"choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"Xin "},{"type":"text","text":"chào"}]}}]}
				""";
		String out = OpenAiContentNormalizer.normalizeBody(body);
		assertThat(out).contains("\"content\":\"Xin chào\"");
	}

	@Test
	void emptyArrayBecomesEmptyString() {
		String body = "{\"choices\":[{\"delta\":{\"content\":[]}}]}";
		assertThat(OpenAiContentNormalizer.normalizeBody(body)).contains("\"content\":\"\"");
	}

	@Test
	void plainStringContentIsUntouched() {
		String body = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
		assertThat(OpenAiContentNormalizer.normalizeBody(body)).isEqualTo(body);
	}

	@Test
	void normalizesEachSseDataLine() {
		String sse = "data: {\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"Hi\"}]}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\" there\"}]}}]}\n\n"
				+ "data: [DONE]\n\n";
		String out = OpenAiContentNormalizer.normalizeBody(sse);
		assertThat(out).contains("\"content\":\"Hi\"").contains("\"content\":\" there\"")
				.contains("data: [DONE]");
	}

	@Test
	void unparseableBodyIsReturnedAsIs() {
		assertThat(OpenAiContentNormalizer.normalizeBody("not json")).isEqualTo("not json");
	}
}

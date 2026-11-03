package com.meetingroom.core.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolNamePrefixTest {

	@Test
	void prefixesAgentAndToolWithDoubleUnderscore() {
		assertThat(ToolNamePrefix.apply("search", "searchRooms")).isEqualTo("search__searchRooms");
	}

	@Test
	void sanitizesIllegalCharacters() {
		assertThat(ToolNamePrefix.apply("room catalog", "list rooms!")).isEqualTo("room_catalog__list_rooms_");
	}

	@Test
	void keepsWithinSixtyFourCharacters() {
		String name = ToolNamePrefix.apply("a".repeat(80), "x".repeat(30));
		assertThat(name).hasSizeLessThanOrEqualTo(ToolNamePrefix.MAX_LENGTH);
		assertThat(name).endsWith("__" + "x".repeat(30));
	}

	@Test
	void truncatesRawNameWhenItAloneIsTooLong() {
		String raw = "t".repeat(63);
		String name = ToolNamePrefix.apply("search", raw);
		assertThat(name).hasSizeLessThanOrEqualTo(ToolNamePrefix.MAX_LENGTH);
		assertThat(name).contains("__");
	}

	@Test
	void fallsBackForEmptySegments() {
		assertThat(ToolNamePrefix.apply("", "")).isEqualTo("agent__tool");
	}

	@Test
	void extractsAgentFromPrefixedName() {
		assertThat(ToolNamePrefix.agentOf("search__searchRooms")).isEqualTo("search");
		assertThat(ToolNamePrefix.agentOf("noprefix")).isNull();
	}
}

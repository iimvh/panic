package com.meetingroom.core.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClasspathPromptRepositoryTest {

	private final ClasspathPromptRepository prompts = new ClasspathPromptRepository();

	@Test
	void readsPromptBundledWithAgent() {
		assertThat(prompts.get("agents/booking/system.md"))
				.isPresent()
				.get()
				.satisfies(text -> assertThat(text).contains("{now}").contains("meeting room"));
	}

	@Test
	void unknownLocationIsEmpty() {
		assertThat(prompts.get("agents/khong-ton-tai/system.md")).isEmpty();
		assertThat(prompts.get("")).isEmpty();
		assertThat(prompts.get(null)).isEmpty();
	}

	@Test
	void everyBundledAgentHasPromptAndMatchingPromptLocation() {
		for (String agent : new String[] { "coordinator", "search", "booking", "cancellation", "roomCatalog" }) {
			assertThat(prompts.get("agents/" + agent + "/system.md"))
					.as("prompt of agent %s", agent)
					.isPresent();
		}
	}
}

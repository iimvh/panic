package com.panic.core.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.panic.core.agent.spi.AgentDefinition;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRegistryTest {

	private static AgentDefinition leaf(String id) {
		return AgentDefinition.builder(id).description(id + " agent").build();
	}

	private static AgentDefinition parent(String id, String... subs) {
		return AgentDefinition.builder(id).subAgentIds(subs).build();
	}

	@Test
	void buildsTreeWithVisibleSubAgents() {
		AgentRegistry registry = new AgentRegistry(
				List.of(parent("coordinator", "search", "booking"),
						parent("search", "detail"),
						leaf("booking"),
						leaf("detail")),
				"coordinator");

		assertThat(registry.root().id()).isEqualTo("coordinator");
		assertThat(registry.visibleSubAgents("coordinator")).extracting(AgentDefinition::id)
				.containsExactly("search", "booking");
		assertThat(registry.reachable()).containsExactlyInAnyOrder("coordinator", "search", "booking", "detail");
	}

	@Test
	void subAgentWhoseModuleIsNotOnTheClasspathIsDropped() {
		// Agents live in optional modules: a declaration may name an agent whose module is absent.
		AgentRegistry registry = new AgentRegistry(List.of(parent("coordinator", "ghost")), "coordinator");

		assertThat(registry.visibleSubAgents("coordinator")).isEmpty();
		assertThat(registry.missingSubAgents("coordinator")).containsExactly("ghost");
		assertThat(registry.reachable()).containsExactly("coordinator");
	}

	@Test
	void failsFastOnCycle() {
		assertThatThrownBy(() -> new AgentRegistry(
				List.of(parent("coordinator", "a"), parent("a", "b"), parent("b", "a")), "coordinator"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Agent cycle");
	}

	@Test
	void failsFastOnDuplicateId() {
		assertThatThrownBy(() -> new AgentRegistry(List.of(leaf("x"), leaf("x")), "x"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Duplicate agent id");
	}

	@Test
	void selfReferenceIsRejectedAtDefinitionTime() {
		assertThatThrownBy(() -> AgentDefinition.builder("a").subAgentIds("a").build())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cannot be its own sub-agent");
	}

	@Test
	void disabledAgentIsDroppedFromTreeButNotAnError() {
		// "booking" is declared but disabled so it has no definition; declared ids include it.
		AgentRegistry registry = new AgentRegistry(
				List.of(parent("coordinator", "search", "booking"), leaf("search")),
				"coordinator", Set.of("booking"));

		assertThat(registry.visibleSubAgents("coordinator")).extracting(AgentDefinition::id)
				.containsExactly("search");
		assertThat(registry.disabledSubAgents("coordinator")).containsExactly("booking");
	}

	@Test
	void unknownRootFailsFast() {
		assertThatThrownBy(() -> new AgentRegistry(List.of(leaf("search")), "coordinatr"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.agents.root='coordinatr' does not exist");
	}

	@Test
	void disabledRootStillStartsButIsReported() {
		AgentRegistry registry = new AgentRegistry(List.of(leaf("search")), "coordinator", Set.of("coordinator"));

		assertThat(registry.isRootEnabled()).isFalse();
		assertThatThrownBy(registry::root)
				.isInstanceOf(com.panic.exception.BookingException.class)
				.hasMessageContaining("Root agent 'coordinator' is disabled");
	}
}

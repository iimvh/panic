package com.meetingroom.core.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class AgentCatalogPropertiesTest {

	private static StandardEnvironment env(Map<String, Object> props) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test", props));
		return env;
	}

	@Test
	void readsRootAndPerAgentEnabled() {
		AgentCatalogProperties catalog = AgentRuntimeConfig.catalog(env(Map.of(
				"app.agents.root", "coordinator",
				"app.agents.booking.enabled", "false",
				"app.agents.search.enabled", "true")));

		assertThat(catalog.root()).isEqualTo("coordinator");
		assertThat(catalog.declaredIds()).containsExactlyInAnyOrder("booking", "search");
		assertThat(catalog.isEnabled("booking")).isFalse();
		assertThat(catalog.isEnabled("search")).isTrue();
	}

	@Test
	void defaultsRootAndTreatsMissingFlagAsEnabled() {
		AgentCatalogProperties catalog = AgentRuntimeConfig.catalog(env(Map.of()));

		assertThat(catalog.root()).isEqualTo("coordinator");
		assertThat(catalog.declaredIds()).isEmpty();
		assertThat(catalog.isEnabled("anything")).isTrue();
	}
}

package com.meetingroom;

import static org.assertj.core.api.Assertions.assertThat;

import com.meetingroom.core.agent.runtime.AgentRegistry;
import com.meetingroom.core.agent.runtime.AgentTreeEndpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("wiring")
class AgentTreeWiringTest {

	@Autowired
	private AgentRegistry registry;

	@Autowired
	private AgentTreeEndpoint endpoint;

	@Test
	void coordinatorIsRootAndSearchIsVisible() {
		assertThat(registry.root().id()).isEqualTo("coordinator");
		assertThat(registry.visibleSubAgents("coordinator")).extracting(a -> a.id())
				.contains("search", "cancellation");
	}

	@Test
	void disabledBookingIsNotInTheTreeNorMountable() {
		assertThat(registry.find("booking")).isEmpty();
		assertThat(registry.visibleSubAgents("coordinator")).extracting(a -> a.id()).doesNotContain("booking");
		assertThat(registry.disabledSubAgents("coordinator")).containsExactly("booking");
	}

	@SuppressWarnings("unchecked")
	@Test
	void actuatorEndpointHidesDisabledAgent() {
		Map<String, Object> tree = endpoint.tree();
		assertThat(tree.get("rootEnabled")).isEqualTo(true);

		List<Map<String, Object>> agents = (List<Map<String, Object>>) tree.get("agents");
		assertThat(agents).extracting(a -> a.get("id")).doesNotContain("booking");

		Map<String, Object> coordinator = agents.stream()
				.filter(a -> "coordinator".equals(a.get("id")))
				.findFirst().orElseThrow();
		assertThat((List<String>) coordinator.get("disabledSubAgents")).containsExactly("booking");
	}

	@SuppressWarnings("unchecked")
	@Test
	void coordinatorTreeContainsSearchChild() {
		Map<String, Object> tree = endpoint.tree();
		Map<String, Object> root = (Map<String, Object>) tree.get("tree");
		List<Map<String, Object>> subs = (List<Map<String, Object>>) root.get("subAgents");
		assertThat(subs).extracting(s -> s.get("id")).contains("search").doesNotContain("booking", "coordinator");
	}
}

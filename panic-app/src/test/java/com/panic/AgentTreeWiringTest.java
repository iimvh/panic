package com.panic;

import static org.assertj.core.api.Assertions.assertThat;

import com.panic.core.agent.runtime.AgentRegistry;
import com.panic.core.agent.runtime.AgentTreeEndpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the "agent = module" contract from the app's point of view: every agent module on the
 * classpath shows up in the tree, and the tree is described entirely by the declarations.
 */
@SpringBootTest
@ActiveProfiles("wiring")
class AgentTreeWiringTest {

	@Autowired
	private AgentRegistry registry;

	@Autowired
	private AgentTreeEndpoint endpoint;

	@Test
	void coordinatorIsRootAndExampleAgentIsMounted() {
		assertThat(registry.root().id()).isEqualTo("coordinator");
		assertThat(registry.visibleSubAgents("coordinator")).extracting(a -> a.id())
				.contains("echo");
		assertThat(registry.missingSubAgents("coordinator")).isEmpty();
	}

	@SuppressWarnings("unchecked")
	@Test
	void exampleAgentHasItsOwnToolSource() {
		var echo = registry.require("echo");
		// The module's YAML declares "tools: [local:echo-tools]"; the loader resolves it against
		// the source the module contributes. A tool source id is the module's own contract, so it
		// must be resolvable without touching panic-core.
		assertThat(echo.toolSources()).hasSize(1);

		Map<String, Object> tree = endpoint.tree();
		List<Map<String, Object>> agents = (List<Map<String, Object>>) tree.get("agents");
		Map<String, Object> echoNode = agents.stream()
				.filter(a -> "echo".equals(a.get("id")))
				.findFirst().orElseThrow();
		assertThat((List<String>) echoNode.get("tools")).containsExactly("echo__echo");
	}

	@SuppressWarnings("unchecked")
	@Test
	void actuatorShowsTheTreeAndTheMountedChild() {
		Map<String, Object> tree = endpoint.tree();
		assertThat(tree.get("rootEnabled")).isEqualTo(true);

		Map<String, Object> root = (Map<String, Object>) tree.get("tree");
		List<Map<String, Object>> subs = (List<Map<String, Object>>) root.get("subAgents");
		assertThat(subs).extracting(s -> s.get("id")).containsExactly("echo");
	}
}

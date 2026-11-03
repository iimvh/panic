package com.meetingroom.core.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Agent plugin configuration: {@code app.agents.root} and {@code app.agents.<id>.enabled}.
 * <p>
 * {@code @ConfigurationProperties} is not used because {@code app.agents.root} (a single value)
 * and {@code app.agents.<id>.*} (a nested map) conflict in type when bound under the same prefix.
 * Instead, {@code *.enabled} keys are read directly from {@link Environment}; this keeps the
 * declared id list (including disabled agents) separate from "misspelled subAgent ids".
 */
public class AgentCatalogProperties {

	private final String root;
	private final Map<String, Boolean> enabled;

	public AgentCatalogProperties(String root, Map<String, Boolean> enabled) {
		this.root = root == null || root.isBlank() ? "coordinator" : root;
		this.enabled = new LinkedHashMap<>(enabled == null ? Map.of() : enabled);
	}

	/** Reads {@code app.agents.*} from the Spring environment. */
	public static AgentCatalogProperties from(Environment environment, String... knownIds) {
		Map<String, Boolean> flags = new LinkedHashMap<>();
		for (String id : knownIds) {
			String key = "app.agents." + id + ".enabled";
			String value = environment.getProperty(key);
			if (value != null) {
				flags.put(id, Boolean.parseBoolean(value));
			}
		}
		String root = environment.getProperty("app.agents.root", "coordinator");
		return new AgentCatalogProperties(root, flags);
	}

	public String root() {
		return root;
	}

	public List<String> declaredIds() {
		return new ArrayList<>(enabled.keySet());
	}

	public boolean isEnabled(String id) {
		return enabled.getOrDefault(id, true);
	}
}

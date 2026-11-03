package com.meetingroom.core.agent.spi;

import java.util.List;

/**
 * A skill = a markdown file with frontmatter. The agent only sees {@code name} + {@code description};
 * the full content is loaded when the LLM calls the {@code load_skill} tool.
 */
public record Skill(
		String id,
		String name,
		String description,
		String content,
		List<String> requiredMcpServers) {

	public Skill {
		requiredMcpServers = requiredMcpServers == null ? List.of() : List.copyOf(requiredMcpServers);
	}
}

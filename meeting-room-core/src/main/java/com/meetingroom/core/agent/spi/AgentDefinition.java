package com.meetingroom.core.agent.spi;

import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for a plugin agent's metadata.
 * <p>
 * A coordinator is just a definition with a non-empty {@code subAgentIds}; a leaf agent only has
 * {@code toolSources}. The tree is wired explicitly in {@code AgentRegistry}.
 * <p>
 * The system prompt is declared as a <b>location</b> ({@code promptFile}), not as content: the
 * content is resolved at render time through {@link PromptRepository}. {@code inlinePrompt} is
 * only meant for tests or special cases where an agent needs no prompt file.
 */
public record AgentDefinition(
		String id,
		String description,
		String promptFile,
		String inlinePrompt,
		List<ToolSource> toolSources,
		List<String> subAgentIds,
		List<String> skillIds,
		AgentPolicy policy) implements Agent {

	public AgentDefinition {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("agent id must not be blank");
		}
		if (!id.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
			throw new IllegalArgumentException("agent id '" + id
					+ "' is invalid (letters, digits, '_' and '-' only, must start with a letter)");
		}
		if (inlinePrompt == null) {
			inlinePrompt = "";
		}
		toolSources = toolSources == null ? List.of() : List.copyOf(toolSources);
		subAgentIds = subAgentIds == null ? List.of() : List.copyOf(subAgentIds);
		skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
		policy = policy == null ? AgentPolicy.DEFAULT : policy;
		if (subAgentIds.contains(id)) {
			throw new IllegalArgumentException("agent '" + id + "' cannot be its own sub-agent");
		}
	}

	/**
	 * Raw template: the inline prompt if present, otherwise read from {@code promptLocation}.
	 * Returns an empty string when both are blank — the caller decides whether to fail fast.
	 */
	public String promptTemplate(PromptRepository prompts) {
		if (!inlinePrompt.isBlank()) {
			return inlinePrompt;
		}
		if (promptFile == null || promptFile.isBlank()) {
			return "";
		}
		return prompts.get(promptFile).orElse("");
	}

	public Optional<String> promptLocation() {
		return Optional.ofNullable(promptFile).filter(value -> !value.isBlank());
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static final class Builder {

		private final String id;
		private String description = "";
		private String promptFile;
		private String inlinePrompt = "";
		private List<ToolSource> toolSources = List.of();
		private List<String> subAgentIds = List.of();
		private List<String> skillIds = List.of();
		private AgentPolicy policy = AgentPolicy.DEFAULT;

		private Builder(String id) {
			this.id = id;
		}

		public Builder description(String value) {
			this.description = value;
			return this;
		}

		/** Declares where the prompt lives, e.g. {@code agents/booking/system.md}. */
		public Builder promptLocation(String value) {
			this.promptFile = value;
			return this;
		}

		/** Inline prompt — for tests only; production should use {@link #promptLocation(String)}. */
		public Builder systemPromptTemplate(String value) {
			this.inlinePrompt = value;
			return this;
		}

		public Builder toolSources(ToolSource... value) {
			this.toolSources = List.of(value);
			return this;
		}

		public Builder toolSources(List<ToolSource> value) {
			this.toolSources = value;
			return this;
		}

		public Builder addToolSource(ToolSource value) {
			List<ToolSource> merged = new java.util.ArrayList<>(this.toolSources);
			merged.add(value);
			this.toolSources = merged;
			return this;
		}

		/** Prompt path relative to the agent directory, e.g. {@code system.md}. */
		public Builder promptFile(String value) {
			this.promptFile = value;
			return this;
		}

		public Builder subAgentIds(String... value) {
			this.subAgentIds = List.of(value);
			return this;
		}

		public Builder subAgentIds(List<String> value) {
			this.subAgentIds = value;
			return this;
		}

		public Builder skillIds(String... value) {
			this.skillIds = List.of(value);
			return this;
		}

		public Builder skillIds(List<String> value) {
			this.skillIds = value;
			return this;
		}

		public Builder policy(AgentPolicy value) {
			this.policy = value;
			return this;
		}

		public AgentDefinition build() {
			return new AgentDefinition(id, description, promptFile, inlinePrompt, toolSources, subAgentIds,
					skillIds, policy);
		}
	}
}

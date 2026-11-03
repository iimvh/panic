package com.meetingroom.core.agent.runtime;

import com.meetingroom.core.agent.spi.AgentDefinition;
import com.meetingroom.core.agent.spi.PromptRepository;
import com.meetingroom.core.agent.spi.Skill;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders {@code systemPromptTemplate} on every turn (never frozen at bean creation).
 * Placeholder: {@code {now}}, {@code {today}}, {@code {tools}}, {@code {subAgents}}, {@code {skills}}.
 * <p>
 * Template content is read from {@link PromptRepository} via the agent's {@code promptLocation},
 * so changing a prompt only requires editing the markdown file.
 */
public final class AgentPromptRenderer {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE");

	private final ZoneId zone;
	private final PromptRepository prompts;

	public AgentPromptRenderer(PromptRepository prompts) {
		this(prompts, ZoneId.systemDefault());
	}

	public AgentPromptRenderer(PromptRepository prompts, ZoneId zone) {
		this.prompts = prompts;
		this.zone = zone;
	}

	/**
	 * @param tools     rendered lines of mounted tools (already prefixed)
	 * @param subAgents rendered lines of enabled sub-agents
	 * @param skills    skills declared by the agent (name + description only)
	 */
	public String render(AgentDefinition agent, List<String> tools, List<String> subAgents, List<Skill> skills) {
		LocalDateTime now = LocalDateTime.now(zone);
		Map<String, String> values = Map.of(
				"now", TIME.format(now),
				"today", DAY.format(now),
				"tools", bullets(tools, "No direct tools."),
				"subAgents", bullets(subAgents, "No sub-agents."),
				"skills", bullets(skills.stream().map(this::describeSkill).toList(), "No skills."));
		return fill(agent.promptTemplate(prompts), values);
	}

	public String describeSkill(Skill skill) {
		return skill.name() + ": " + skill.description();
	}

	public String describeSubAgent(AgentDefinition sub) {
		return sub.id() + ": " + sub.description();
	}

	private static String bullets(List<String> lines, String empty) {
		if (lines == null || lines.isEmpty()) {
			return empty;
		}
		return lines.stream().map(line -> "- " + line).collect(Collectors.joining("\n"));
	}

	/** Single pass, does NOT rescan the output (tool values may contain '{'). */
	static String fill(String template, Map<String, String> values) {
		if (template == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(template.length() + 256);
		int i = 0;
		while (i < template.length()) {
			char c = template.charAt(i);
			if (c == '{') {
				int end = template.indexOf('}', i + 1);
				if (end > i) {
					String value = values.get(template.substring(i + 1, end));
					if (value != null) {
						out.append(value);
						i = end + 1;
						continue;
					}
				}
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}
}

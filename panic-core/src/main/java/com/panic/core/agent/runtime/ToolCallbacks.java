package com.panic.core.agent.runtime;

import com.panic.core.agent.spi.AgentDefinition;
import com.panic.core.agent.spi.Skill;
import com.panic.core.agent.spi.SkillRepository;
import com.panic.core.agent.spi.ToolSource;
import com.panic.exception.BookingException;
import com.panic.pipeline.PipelineEvent;
import com.panic.pipeline.PipelineJson;
import com.panic.pipeline.PipelineRecorder;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Dynamically generated tools used by the agent runtime:
 * <ul>
 *   <li>each enabled sub-agent = exactly one tool {@code call_<subId>} (leaf tools are not flattened into the parent);</li>
 *   <li>{@code load_skill} if the agent has skills.</li>
 * </ul>
 */
final class ToolCallbacks {

	public static final String CALL_PREFIX = "call_";
	public static final String LOAD_SKILL = "load_skill";

	private static final Logger log = LoggerFactory.getLogger(ToolCallbacks.class);
	private static final String QUERY_SCHEMA = "{\"type\":\"object\",\"properties\":{\"query\":"
			+ "{\"type\":\"string\",\"description\":\"Yeu cau nguoi dung bang ngon ngu tu nhien\"}},"
			+ "\"required\":[\"query\"]}";
	private static final String SKILL_SCHEMA = "{\"type\":\"object\",\"properties\":{\"skillId\":"
			+ "{\"type\":\"string\",\"description\":\"id skill lay tu danh sach skills\"}},\"required\":[\"skillId\"]}";

	private ToolCallbacks() {
	}

	/** The agent's tools (local or MCP), prefixed, recording each source in the pipeline. */
	static List<ToolCallback> ownTools(AgentDefinition agent, PipelineRecorder recorder,
			PipelineRecorder.RequestTrace trace) {
		List<ToolCallback> result = new java.util.ArrayList<>();
		for (ToolSource source : agent.toolSources()) {
			long start = System.currentTimeMillis();
			String status = PipelineEvent.STATUS_OK;
			String detail;
			List<ToolCallback> callbacks;
			try {
				callbacks = source.toolCallbacks();
				detail = PipelineJson.of(Map.of("provider", source.provider().name(),
						"tools", callbacks.stream().map(c -> c.getToolDefinition().name()).toList()));
			} catch (RuntimeException ex) {
				callbacks = List.of();
				status = PipelineEvent.STATUS_ERROR;
				detail = PipelineJson.text(String.valueOf(ex.getMessage()));
				log.warn("ToolSource '{}' of agent '{}' failed: {}", source.id(), agent.id(), ex.getMessage());
			}
			recorder.emitDone(trace, PipelineEvent.PHASE_TOOL, status, agent.id(),
					"Tool source " + source.id() + " (" + source.provider() + ")", detail,
					System.currentTimeMillis() - start, agent.id());
			callbacks.stream().map(callback -> new PrefixedToolCallback(callback, agent.id())).forEach(result::add);
		}
		return result;
	}

	/**
	 * Common tools granted from the tool-plugin hub ({@code hubTools} in YAML). Names are prefixed
	 * like built-in tools. A granted plugin that is unavailable (missing config) is skipped with a
	 * warn so the agent still runs; a plugin whose callbacks throw contributes none.
	 */
	static List<ToolCallback> hubTools(AgentDefinition agent, ToolPluginHub hub, PipelineRecorder recorder,
			PipelineRecorder.RequestTrace trace) {
		List<ToolCallback> result = new java.util.ArrayList<>();
		for (String pluginId : agent.hubToolIds()) {
			var plugin = hub.find(pluginId).orElse(null);
			if (plugin == null) {
				log.warn("agent '{}' was granted hub tool '{}' but it is not registered", agent.id(), pluginId);
				continue;
			}
			if (!plugin.available()) {
				recorder.emitDone(trace, PipelineEvent.PHASE_TOOL, PipelineEvent.STATUS_ERROR, agent.id(),
						"Hub tool " + pluginId + " unavailable", PipelineJson.text("granted but not available"),
						0, agent.id());
				continue;
			}
			long start = System.currentTimeMillis();
			String status = PipelineEvent.STATUS_OK;
			String detail;
			List<ToolCallback> callbacks;
			try {
				callbacks = plugin.toolCallbacks();
				detail = PipelineJson.of(Map.of("source", "hub", "plugin", pluginId,
						"tools", callbacks.stream().map(c -> c.getToolDefinition().name()).toList()));
			} catch (RuntimeException ex) {
				callbacks = List.of();
				status = PipelineEvent.STATUS_ERROR;
				detail = PipelineJson.text(String.valueOf(ex.getMessage()));
				log.warn("Hub tool '{}' of agent '{}' failed: {}", pluginId, agent.id(), ex.getMessage());
			}
			recorder.emitDone(trace, PipelineEvent.PHASE_TOOL, status, agent.id(),
					"Hub tool " + pluginId, detail, System.currentTimeMillis() - start, agent.id());
			callbacks.stream().map(callback -> new PrefixedToolCallback(callback, agent.id())).forEach(result::add);
		}
		return result;
	}

	/** A sub-agent = one tool {@code call_<id>}; the description comes from AgentDefinition. */
	static ToolCallback subAgentTool(AgentDefinition parent, AgentDefinition sub) {
		String name = CALL_PREFIX + sub.id();
		String description = "Delegate the request to agent '" + sub.id() + "'. " + sub.description();
		return new FunctionToolCallback(name, description, QUERY_SCHEMA,
				input -> AgentCall.queryOf(input).query(), parent.id());
	}

	/** Tool that loads a skill's full text by id (progressive disclosure). */
	static ToolCallback loadSkillTool(AgentDefinition agent, SkillRepository skills, PipelineRecorder recorder,
			PipelineRecorder.RequestTrace trace) {
		return new FunctionToolCallback(LOAD_SKILL,
				"Load the full procedure of one skill from the skills list. Call it when you need details before answering.",
				SKILL_SCHEMA, input -> {
					String skillId = AgentCall.skillIdOf(input);
					long start = System.currentTimeMillis();
					Skill skill = skills.get(skillId).orElse(null);
					if (skill == null) {
						recorder.emitDone(trace, PipelineEvent.PHASE_PARSE, PipelineEvent.STATUS_ERROR, agent.id(),
								"load_skill not found", PipelineJson.text(skillId), 0, agent.id());
						throw new BookingException("Skill '" + skillId + "' does not exist");
					}
					recorder.emitDone(trace, PipelineEvent.PHASE_PARSE, PipelineEvent.STATUS_OK, agent.id(),
							"Loaded skill " + skill.id(),
							PipelineJson.of(Map.of("skill", skill.id(), "name", skill.name())),
							System.currentTimeMillis() - start, agent.id());
					return "Skill " + skill.name() + "\n\n" + skill.content();
				}, agent.id());
	}

	/** Simple callback around a function; used for runtime-generated tools. */
	static final class FunctionToolCallback implements ToolCallback {

		private final ToolDefinition definition;
		private final java.util.function.Function<String, String> action;
		private final String agentId;

		FunctionToolCallback(String name, String description, String schema,
				java.util.function.Function<String, String> action, String agentId) {
			this.definition = ToolDefinition.builder().name(name).description(description)
					.inputSchema(schema).build();
			this.action = action;
			this.agentId = agentId;
		}

		String agentId() {
			return agentId;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return definition;
		}

		@Override
		public String call(String toolInput) {
			return action.apply(toolInput);
		}

		@Override
		public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
			return action.apply(toolInput);
		}
	}
}

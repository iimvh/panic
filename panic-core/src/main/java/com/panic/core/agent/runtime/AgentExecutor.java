package com.panic.core.agent.runtime;

import com.panic.core.agent.memory.ChatTurn;
import com.panic.core.agent.memory.ConversationStore;
import com.panic.core.agent.spi.AgentContext;
import com.panic.core.agent.spi.AgentDefinition;
import com.panic.core.agent.spi.AgentResult;
import com.panic.core.agent.spi.ProviderKind;
import com.panic.core.agent.spi.Skill;
import com.panic.core.agent.spi.SkillRepository;
import com.panic.exception.BookingException;
import com.panic.pipeline.LlmCallAdvisor;
import com.panic.pipeline.PipelineEvent;
import com.panic.pipeline.PipelineJson;
import com.panic.pipeline.PipelineRecorder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LLM + tool loop of ONE agent, recursing into sub-agents.
 * <p>
 * Agents are stateless: everything (trace, parent event, depth, chain) travels through {@link AgentContext}.
 * Tools the LLM sees are always prefixed {@code <agentId>__}; each enabled sub-agent is mounted
 * as exactly one {@code call_<subId>} tool.
 */
@Component
public class AgentExecutor {

	private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

	private final ChatClient.Builder chatClientBuilder;
	private final AgentRegistry registry;
	private final SkillRepository skills;
	private final PipelineRecorder recorder;
	private final AgentPromptRenderer renderer;
	private final ConversationStore memory;
	private final boolean aiEnabled;

	public AgentExecutor(ChatClient.Builder chatClientBuilder, AgentRegistry registry, SkillRepository skills,
			PipelineRecorder recorder, AgentPromptRenderer renderer, ConversationStore memory,
			@Value("${spring.ai.openai.api-key:}") String apiKey) {
		this.chatClientBuilder = chatClientBuilder;
		this.registry = registry;
		this.skills = skills;
		this.recorder = recorder;
		this.renderer = renderer;
		this.memory = memory;
		this.aiEnabled = AgentApiKey.isConfigured(apiKey);
	}

	/** Root run without conversation memory (used by tests and any stateless caller). */
	public AgentResult runRoot(String userMessage) {
		return runRoot(null, userMessage);
	}

	/**
	 * Root run with conversation memory: loads the conversation's history into the root prompt and
	 * appends this turn (user + assistant) afterwards. Sub-agents never read or write memory.
	 */
	public AgentResult runRoot(String conversationId, String userMessage) {
		PipelineRecorder.RequestTrace trace = recorder.currentTrace();
		long traceId = trace == null ? 0L : trace.id();
		AgentDefinition root = registry.root();
		AgentResult result = run(root, AgentContext.root(traceId, userMessage, conversationId));
		if (memory != null && conversationId != null && !conversationId.isBlank()) {
			memory.append(conversationId, ChatTurn.Role.USER, userMessage);
			memory.append(conversationId, ChatTurn.Role.ASSISTANT, result.text());
		}
		return result;
	}

	/**
	 * Runs one agent. {@code context.chain()} holds the agents that already ran (excluding this
	 * one); each occurrence is a return via a different branch — blocked when >= 2.
	 */
	public AgentResult run(AgentDefinition agent, AgentContext context) {
		guard(agent, context);
		if (!aiEnabled) {
			throw new BookingException("Agent AI is not enabled: please set OPENROUTER_API_KEY");
		}
		PipelineRecorder.RequestTrace trace = recorder.currentTrace();
		List<AgentDefinition> callable = new ArrayList<>(registry.visibleSubAgents(agent.id()));
		List<Skill> agentSkills = skills.get(agent.skillIds());
		warnMissingMcpSkills(agent, agentSkills);

		List<ToolCallback> tools = new ArrayList<>(ToolCallbacks.ownTools(agent, recorder, trace));
		Map<String, AgentDefinition> subById = new LinkedHashMap<>();
		for (AgentDefinition sub : callable) {
			ToolCallback callback = ToolCallbacks.subAgentTool(agent, sub);
			tools.add(callback);
			subById.put(callback.getToolDefinition().name(), sub);
		}
		if (!agentSkills.isEmpty()) {
			tools.add(ToolCallbacks.loadSkillTool(agent, skills, recorder, trace));
		}

		List<String> toolLines = new ArrayList<>();
		for (ToolCallback callback : tools) {
			ToolDefinition definition = callback.getToolDefinition();
			toolLines.add(definition.name() + ": " + definition.description());
		}
		String systemPrompt = renderer.render(agent, toolLines,
				callable.stream().map(renderer::describeSubAgent).toList(), agentSkills);
		if (!StringUtils.hasText(systemPrompt)) {
			throw new BookingException(agent.promptLocation()
					.map(location -> "Agent '" + agent.id() + "' is missing its system prompt: cannot read '"
							+ location + "'")
					.orElse("Agent '" + agent.id() + "' does not declare a system prompt"));
		}

		PipelineRecorder.ActiveAgent previous = recorder.pushAgent(agent.id(), context.depth(),
				context.parentEventId());
		try {
			if (trace != null) {
				// The rendered system prompt as a SYSTEM entry in the trajectory stream.
				recorder.emitDone(trace, PipelineEvent.PHASE_MESSAGE, PipelineEvent.STATUS_OK, agent.id(),
						"System prompt", PipelineJson.of(Map.of("text", systemPrompt)), 0, agent.id(),
						PipelineEvent.ROLE_SYSTEM);
				// A child agent's event must sit under the parent's tool call so the UI draws the tree right.
				recorder.emit(trace, trace.builder().next(PipelineEvent.PHASE_TRACE,
						PipelineEvent.STATUS_OK, agent.id(), "Running agent " + agent.id(),
						PipelineJson.of(Map.of("depth", context.depth(),
								"tools", tools.stream().map(c -> c.getToolDefinition().name()).toList(),
								"skills", agent.skillIds())),
						context.parentEventId(), agent.id(), context.depth(), PipelineEvent.ROLE_CONTEXT));
			}
			List<ToolCallback> routed = tools.stream()
					.map(callback -> route(agent, callback, subById, context))
					.toList();
			// Options must be ToolCallingChatOptions for ChatClient to merge tool callbacks
			// into the prompt for the ChatModel (independent of the app's auto-config).
			ChatClient chat = chatClientBuilder.clone()
					.defaultOptions(DefaultToolCallingChatOptions.builder().build())
					.build();
			ChatClient.ChatClientRequestSpec prompt = chat.prompt();
			prompt.system(systemPrompt);
			// Only the root agent replays conversation history; sub-agents get just the parent's task.
			if (memory != null && context.depth() == 0 && context.conversationId() != null
					&& !context.conversationId().isBlank()) {
				List<org.springframework.ai.chat.messages.Message> history =
						memory.loadMessages(context.conversationId());
				if (!history.isEmpty()) {
					prompt.messages(history);
				}
			}
			String reply = prompt
					.user(context.userMessage())
					.toolCallbacks(routed)
					.toolContext(Map.of("traceId", context.traceId(), "agentId", agent.id(), "depth", context.depth()))
					.advisors(LlmCallAdvisor.forAgent(agent.id(), recorder, trace, context.parentEventId()))
					.call()
					.content();
			return AgentResult.ok(agent.id(), reply == null ? "" : reply, context.depth());
		} catch (BookingException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw new BookingException("Agent '" + agent.id() + "' (depth=" + context.depth() + ") failed: "
					+ ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
		} finally {
			recorder.popAgent(previous);
		}
	}

	private ToolCallback route(AgentDefinition agent, ToolCallback callback, Map<String, AgentDefinition> subById,
			AgentContext context) {
		AgentDefinition sub = subById.get(callback.getToolDefinition().name());
		return sub == null ? callback : new SubAgentToolCallback(agent, sub, callback, context);
	}

	private void guard(AgentDefinition agent, AgentContext context) {
		if (!StringUtils.hasText(context.userMessage())) {
			throw new BookingException("Message must not be blank");
		}
		if (context.depth() > agent.policy().maxDepth()) {
			throw new BookingException("Agent '" + agent.id() + "' exceeds max depth "
					+ agent.policy().maxDepth() + " (depth=" + context.depth() + ")");
		}
		long occurrences = context.chain().stream().filter(agent.id()::equals).count();
		if (occurrences > 1) {
			throw new BookingException("Agent loop detected: " + String.join(" -> ", context.chain()));
		}
	}

	private void warnMissingMcpSkills(AgentDefinition agent, List<Skill> agentSkills) {
		for (Skill skill : agentSkills) {
			for (String server : skill.requiredMcpServers()) {
				boolean installed = agent.toolSources().stream()
						.anyMatch(source -> source.provider() == ProviderKind.MCP && server.equals(source.id())
								&& source.available());
				if (!installed) {
					log.warn("agent='{}' skill='{}' requires MCP server '{}' which is not enabled; the skill still loads but its tools are missing.",
							agent.id(), skill.id(), server);
				}
			}
		}
	}

	/** {@code call_<subId>} tool: opens a TOOL event (actor = parent), then runs the sub-agent recursively. */
	private final class SubAgentToolCallback implements ToolCallback {

		private final AgentDefinition parent;
		private final AgentDefinition sub;
		private final ToolCallback delegate;
		private final AgentContext parentContext;

		SubAgentToolCallback(AgentDefinition parent, AgentDefinition sub, ToolCallback delegate,
				AgentContext parentContext) {
			this.parent = parent;
			this.sub = sub;
			this.delegate = delegate;
			this.parentContext = parentContext;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return delegate.getToolDefinition();
		}

		@Override
		public String call(String toolInput) {
			return call(toolInput, null);
		}

		@Override
		public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
			String query = AgentCall.queryOf(toolInput).query();
			PipelineRecorder.RequestTrace trace = recorder.currentTrace();
			PipelineEvent toolEvent = recorder.start(trace, PipelineEvent.PHASE_TOOL, parent.id(),
					"Sub-agent " + sub.id(),
					PipelineJson.of(Map.of("args", Map.of("query", String.valueOf(query)))),
					parentContext.parentEventId(), parent.id());
			Long toolEventId = toolEvent == null ? null : toolEvent.id();
			long start = System.currentTimeMillis();
			try {
				AgentContext childContext = parentContext.child(sub.id(), toolEventId, query);
				AgentResult result = run(sub, childContext);
				recorder.finish(trace, toolEvent, PipelineEvent.STATUS_OK, "Sub-agent " + sub.id(),
						PipelineJson.of(Map.of("output", result.text())), System.currentTimeMillis() - start,
						toolEventId);
				return result.text();
			} catch (RuntimeException ex) {
				recorder.finish(trace, toolEvent, PipelineEvent.STATUS_ERROR, "Sub-agent " + sub.id(),
						PipelineJson.text(String.valueOf(ex.getMessage())), System.currentTimeMillis() - start,
						toolEventId);
				throw ex;
			}
		}
	}
}

package com.panic.core.agent.runtime;

import com.panic.core.agent.chat.provider.LlmProviderChatFactory;
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
import com.panic.pipeline.PipelineBroadcaster;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LLM + tool loop of ONE agent, recursing into sub-agents.
 * <p>
 * Agents are stateless: everything (trace, parent event, depth, chain) travels through {@link AgentContext}.
 * Tools the LLM sees are always prefixed {@code <agentId>__}; each enabled sub-agent is mounted
 * as exactly one {@code call_<subId>} tool.
 * <p>
 * The model, its API key and its settings come from the provider the workspace connected in
 * the Settings panel ({@link LlmProviderChatFactory}); every run resolves the active
 * connection anew, so a Settings change applies from the next request on while a
 * streaming run keeps the client it started with.
 */
@Component
public class AgentExecutor {

	private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

	private final LlmProviderChatFactory chatFactory;
	private final AgentRegistry registry;
	private final SkillRepository skills;
	private final PipelineRecorder recorder;
	private final AgentPromptRenderer renderer;
	private final ConversationStore memory;
	private final PipelineBroadcaster broadcaster;

	public AgentExecutor(LlmProviderChatFactory chatFactory, AgentRegistry registry, SkillRepository skills,
			PipelineRecorder recorder, AgentPromptRenderer renderer, ConversationStore memory,
			PipelineBroadcaster broadcaster) {
		this.chatFactory = chatFactory;
		this.registry = registry;
		this.skills = skills;
		this.recorder = recorder;
		this.renderer = renderer;
		this.memory = memory;
		this.broadcaster = broadcaster;
	}

	/** Root run without conversation memory (used by tests and any stateless caller). */
	public AgentResult runRoot(String userMessage) {
		return runRoot(null, userMessage);
	}

	/**
	 * Root run with conversation memory: loads the conversation's history into the root prompt and
	 * appends this turn (user + assistant) afterwards, both stamped with the run's trace id.
	 * A failing run still records the user turn (without an assistant partner) so a replay of the
	 * session shows the question and its failed trace. Sub-agents never read or write memory.
	 */
	public AgentResult runRoot(String conversationId, String userMessage) {
		PipelineRecorder.RequestTrace trace = recorder.currentTrace();
		long traceId = trace == null ? 0L : trace.id();
		AgentDefinition root = registry.root();
		try {
			AgentResult result = run(root, AgentContext.root(traceId, userMessage, conversationId));
			recordTurn(conversationId, ChatTurn.Role.USER, userMessage, traceId);
			recordTurn(conversationId, ChatTurn.Role.ASSISTANT, result.text(), traceId);
			return result;
		} catch (RuntimeException ex) {
			recordTurn(conversationId, ChatTurn.Role.USER, userMessage, traceId);
			throw ex;
		}
	}

	/** Memory is an accretion, not a requirement: a failing write must never fail the chat. */
	private void recordTurn(String conversationId, ChatTurn.Role role, String content, long traceId) {
		if (memory == null || conversationId == null || conversationId.isBlank()
				|| !StringUtils.hasText(content)) {
			return;
		}
		try {
			memory.append(conversationId, role, content, traceId);
		} catch (RuntimeException ex) {
			log.warn("Could not persist {} turn of conversation '{}': {}", role, conversationId,
					ex.getMessage());
		}
	}

	/**
	 * Runs one agent. {@code context.chain()} holds the agents that already ran (excluding this
	 * one); each occurrence is a return via a different branch — blocked when >= 2.
	 */
	public AgentResult run(AgentDefinition agent, AgentContext context) {
		guard(agent, context);
		if (!chatFactory.connected()) {
			throw new BookingException("No LLM provider connected: connect one in the Settings panel");
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
			ChatClient chat = chatFactory.chatClientBuilder()
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
			var callSpec = prompt
					.user(context.userMessage())
					.toolCallbacks(routed)
					.toolContext(Map.of("traceId", context.traceId(), "agentId", agent.id(), "depth", context.depth()))
					.advisors(LlmCallAdvisor.forAgent(agent.id(), recorder, trace, context.parentEventId()));
			// The root agent streams its answer token by token on the chat stream so the UI can render
			// it as it is generated. Sub-agents run inside a blocking tool callback, so they stay blocking
			// (their result travels back through the tool response).
			boolean stream = context.depth() == 0 && broadcaster != null && trace != null;
			String reply;
			if (stream) {
				reply = streamAnswer(callSpec, agent.id(), context);
				if (reply == null) {
					// Model without streaming support (or a test double): answer in one blocking call.
					reply = callSpec.call().content();
				}
			} else {
				reply = callSpec.call().content();
			}
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

	/**
	 * Streams the answer, publishing each chunk. Returns the accumulated text, or {@code null} when
	 * the model does not support streaming and nothing was emitted, so the caller can fall back.
	 */
	private String streamAnswer(ChatClient.ChatClientRequestSpec callSpec, String agentId, AgentContext context) {
		StringBuilder accumulated = new StringBuilder();
		try {
			callSpec.stream().content().toIterable().forEach(chunk -> {
				accumulated.append(chunk);
				broadcaster.publishToken(context.traceId(), chunk, agentId);
			});
			return accumulated.toString();
		} catch (RuntimeException ex) {
			if (accumulated.length() == 0 && ex instanceof UnsupportedOperationException) {
				log.debug("Model does not support streaming for agent '{}'; falling back to a blocking call", agentId);
				return null;
			}
			throw ex;
		}
	}

	private ToolCallback route(AgentDefinition agent, ToolCallback callback, Map<String, AgentDefinition> subById,
			AgentContext context) {
		AgentDefinition sub = subById.get(callback.getToolDefinition().name());
		return sub == null
				? callback
				: new SubAgentToolCallback(agent, sub, callback, context, recorder.currentTrace());
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
		/**
		 * Captured while {@code route()} runs on the caller thread, before the streamed tool loop
		 * moves to a reactor thread where no trace is bound.
		 */
		private final PipelineRecorder.RequestTrace trace;

		SubAgentToolCallback(AgentDefinition parent, AgentDefinition sub, ToolCallback delegate,
				AgentContext parentContext, PipelineRecorder.RequestTrace trace) {
			this.parent = parent;
			this.sub = sub;
			this.delegate = delegate;
			this.parentContext = parentContext;
			this.trace = trace;
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
			PipelineRecorder.RequestTrace boundTrace = boundTrace();
			PipelineEvent toolEvent = recorder.start(boundTrace, PipelineEvent.PHASE_TOOL, parent.id(),
					"Sub-agent " + sub.id(),
					PipelineJson.of(Map.of("args", Map.of("query", String.valueOf(query)))),
					parentContext.parentEventId(), parent.id());
			Long toolEventId = toolEvent == null ? null : toolEvent.id();
			long start = System.currentTimeMillis();
			try {
				AgentContext childContext = parentContext.child(sub.id(), toolEventId, query);
				AgentResult result = recorder.callBound(boundTrace, () -> run(sub, childContext));
				recorder.finish(boundTrace, toolEvent, PipelineEvent.STATUS_OK, "Sub-agent " + sub.id(),
						PipelineJson.of(Map.of("output", result.text())), System.currentTimeMillis() - start,
						toolEventId);
				return result.text();
			} catch (RuntimeException ex) {
				recorder.finish(boundTrace, toolEvent, PipelineEvent.STATUS_ERROR, "Sub-agent " + sub.id(),
						PipelineJson.text(String.valueOf(ex.getMessage())), System.currentTimeMillis() - start,
						toolEventId);
				throw ex;
			}
		}

		/**
		 * The executing thread may be a reactor thread with no trace bound (streamed tool loop);
		 * fall back to the captured trace instead of null.
		 */
		private PipelineRecorder.RequestTrace boundTrace() {
			PipelineRecorder.RequestTrace current = recorder.currentTrace();
			return current != null ? current : trace;
		}
	}
}

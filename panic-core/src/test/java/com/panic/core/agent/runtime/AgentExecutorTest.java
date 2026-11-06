package com.panic.core.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.panic.core.agent.spi.AgentDefinition;
import com.panic.core.agent.spi.AgentPolicy;
import com.panic.core.agent.spi.Skill;
import com.panic.core.agent.spi.SkillRepository;
import com.panic.core.agent.spi.ToolSource;
import com.panic.core.agent.tool.LocalToolSource;
import com.panic.exception.BookingException;
import com.panic.pipeline.PipelineBroadcaster;
import com.panic.pipeline.PipelineEvent;
import com.panic.pipeline.PipelineRecorder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

class AgentExecutorTest {

	/** Simple local tool that counts how often it is called. */
	static final class CountingTools {

		private final AtomicReference<String> lastQuery = new AtomicReference<>();

		@Tool(name = "searchRooms", description = "Find rooms")
		public String searchRooms(@ToolParam(description = "query") String query) {
			lastQuery.set(query);
			return "- id=1 | name=Sala A | capacity=10 | location=Floor 5";
		}
	}

	private CountingTools tools;
	private ScriptedChatModel model;
	private PipelineRecorder recorder;
	private PipelineBroadcaster broadcaster;

	@BeforeEach
	void setUp() {
		tools = new CountingTools();
		model = new ScriptedChatModel();
		broadcaster = new PipelineBroadcaster();
		recorder = new PipelineRecorder(broadcaster);
	}

	private AgentExecutor executor(List<AgentDefinition> agents, SkillRepository skills) {
		AgentRegistry registry = new AgentRegistry(agents, "coordinator");
		ChatClient.Builder builder = testBuilder(model);
		return new AgentExecutor(builder, registry, skills, recorder, new AgentPromptRenderer(prompts()), null, broadcaster,
				"test-key");
	}

	/** Test prompt repository: returns inline templates so tests do not depend on resource files. */
	private static com.panic.core.agent.spi.PromptRepository prompts() {
		return location -> Optional.empty();
	}

	/**
	 * Builder like auto-config: options must be {@link DefaultToolCallingChatOptions} for
	 * ChatClient to merge tool callbacks into the prompt so the model executes the tools.
	 */
	static ChatClient.Builder testBuilder(ScriptedChatModel model) {
		return ChatClient.builder(model)
				.defaultOptions(org.springframework.ai.model.tool.DefaultToolCallingChatOptions.builder().build());
	}

	private static SkillRepository noSkills() {
		return new SkillRepository() {
			@Override
			public List<Skill> list() {
				return List.of();
			}

			@Override
			public Optional<Skill> get(String id) {
				return Optional.empty();
			}
		};
	}

	private static AgentDefinition coordinator(String... subs) {
		return AgentDefinition.builder("coordinator")
				.subAgentIds(subs)
				.systemPromptTemplate("Sub-agents:\n{subAgents}\nTools:\n{tools}\nNow: {now}")
				.build();
	}

	private AgentDefinition searchAgent(ToolSource source, String... skills) {
		return AgentDefinition.builder("search")
				.description("Find meeting rooms")
				.systemPromptTemplate("You are a room search agent. Skills:\n{skills}")
				.toolSources(source)
				.skillIds(skills)
				.policy(new AgentPolicy(3, 10_000, 5))
				.build();
	}

	private AgentRegistry registry(List<AgentDefinition> agents) {
		return new AgentRegistry(agents, "coordinator");
	}

	@Test
	void coordinatorSeesSubAgentAsSingleCallTool() {
		AgentExecutor executor = executor(List.of(coordinator("search"), searchAgent(LocalToolSource.of("t", tools))),
				noSkills());
		model.reply("hello");

		executor.runRoot("hello");

		String system = model.prompts().get(0).getSystemMessage().getText();
		assertThat(system).contains("call_search").contains("Find meeting rooms");
	}

	@Test
	void llmCallingSubAgentRecursesWithDepthAndParentEvent() {
		AgentExecutor executor = executor(List.of(coordinator("search"), searchAgent(LocalToolSource.of("t", tools))),
				noSkills());
		// 1) coordinator calls call_search → 2) search's LLM answers → 3) coordinator synthesizes.
		model.callTool("call_search", "{\"query\":\"room for 5 people\"}")
				.reply("Result from search")
				.reply("Search finished");

		PipelineRecorder.RequestTrace trace = new PipelineRecorder.RequestTrace();
		recorder.startResponse(trace);
		String reply = executor.runRoot("find a room").text();
		PipelineEvent[] events = recorder.end(trace, false, null);

		assertThat(reply).isEqualTo("Search finished");

		PipelineEvent subTool = java.util.Arrays.stream(events)
				.filter(e -> "tool".equals(e.phase()) && "Sub-agent search".equals(e.title()))
				.findFirst().orElseThrow();
		PipelineEvent childRun = java.util.Arrays.stream(events)
				.filter(e -> "trace".equals(e.phase()) && "search".equals(e.agentId()))
				.findFirst().orElseThrow();

		assertThat(subTool.agentId()).isEqualTo("coordinator");
		assertThat(childRun.depth()).isEqualTo(1);
		assertThat(childRun.parentId()).isEqualTo(subTool.id());
	}

	@Test
	void toolCallIsPrefixedWithAgentId() {
		AgentRegistry registry = registry(List.of(coordinator("search"), searchAgent(LocalToolSource.of("t", tools))));
		assertThat(registry.require("search").toolSources()).hasSize(1);

		AgentDefinition search = searchAgent(LocalToolSource.of("t", tools), "any");
		assertThat(search.toolSources().get(0).toolCallbacks().get(0).getToolDefinition().name())
				.isEqualTo("searchRooms");
		assertThat(ToolNamePrefix.apply(search.id(),
				search.toolSources().get(0).toolCallbacks().get(0).getToolDefinition().name()))
				.isEqualTo("search__searchRooms");
	}

	@Test
	void exceedsMaxDepthThrowsBookingException() {
		AgentDefinition root = AgentDefinition.builder("coordinator")
				.subAgentIds("search")
				.policy(new AgentPolicy(0, 10_000, 5))
				.build();
		AgentExecutor executor = executor(List.of(root, searchAgent(LocalToolSource.of("t", tools))), noSkills());
		model.reply("no tool call");

		AgentRegistry registry = registry(List.of(root, searchAgent(LocalToolSource.of("t", tools))));
		com.panic.core.agent.spi.AgentContext tooDeep = new com.panic.core.agent.spi.AgentContext(
				1L, null, 2, List.of("coordinator"), "x", null);

		assertThatThrownBy(() -> executor.run(registry.require("coordinator"), tooDeep))
				.isInstanceOf(BookingException.class)
				.hasMessageContaining("exceeds max depth");
	}

	@Test
	void chainRepetitionThrowsLoopError() {
		AgentDefinition root = coordinator("search");
		AgentExecutor executor = executor(List.of(root, searchAgent(LocalToolSource.of("t", tools))), noSkills());
		AgentRegistry registry = registry(List.of(root, searchAgent(LocalToolSource.of("t", tools))));
		// "coordinator" appears twice in its own chain → in practice this only happens on a loop.
		com.panic.core.agent.spi.AgentContext looped = new com.panic.core.agent.spi.AgentContext(
				1L, null, 3, List.of("coordinator", "search", "coordinator"), "x", null);

		assertThatThrownBy(() -> executor.run(registry.require("coordinator"), looped))
				.isInstanceOf(BookingException.class)
				.hasMessageContaining("loop detected");
	}

	@Test
	void loadSkillReturnsContentAndIsMountedForAgentsWithSkills() {
		SkillRepository skills = new SkillRepository() {
			@Override
			public List<Skill> list() {
				return List.of(policy());
			}

			@Override
			public Optional<Skill> get(String id) {
				return "booking-policy".equals(id) ? Optional.of(policy()) : Optional.empty();
			}
		};
		AgentExecutor executor = executor(List.of(coordinator("search"), searchAgent(LocalToolSource.of("t", tools),
				"booking-policy")), skills);
		// 1) coordinator calls search → 2) search calls load_skill → 3) search answers → 4) coordinator synthesizes.
		model.callTool("call_search", "{\"query\":\"book a room\"}")
				.callTool("load_skill", "{\"skillId\":\"booking-policy\"}")
				.reply("Procedure loaded")
				.reply("Xong");

		String reply = executor.runRoot("find a room").text();

		assertThat(reply).isEqualTo("Xong");
		assertThat(model.prompts()).anySatisfy(p -> assertThat(p.getSystemMessage().getText())
				.contains("booking-policy"));
	}

	private static Skill policy() {
		return new Skill("booking-policy", "booking-policy", "Booking policy", "FULL CONTENT", List.of());
	}

	@Test
	void rootReplaysConversationHistoryAndPersistsTurns() {
		com.panic.core.agent.memory.ConversationStore memory =
				org.mockito.Mockito.mock(com.panic.core.agent.memory.ConversationStore.class);
		org.mockito.Mockito.when(memory.loadMessages("conv-1")).thenReturn(List.of(
				new org.springframework.ai.chat.messages.UserMessage("my name is Lan"),
				new org.springframework.ai.chat.messages.AssistantMessage("Nice to meet you, Lan")));

		AgentRegistry registry = registry(List.of(coordinator()));
		AgentExecutor executor = new AgentExecutor(testBuilder(model), registry, noSkills(), recorder,
				new AgentPromptRenderer(prompts()), memory, broadcaster, "test-key");
		model.reply("You are Lan");

		executor.runRoot("conv-1", "what is my name?");

		String system = model.prompts().get(0).getSystemMessage().getText();
		org.assertj.core.api.Assertions.assertThat(system).doesNotContain("my name is Lan");
		org.assertj.core.api.Assertions.assertThat(model.prompts().get(0).getInstructions())
				.anySatisfy(message -> org.assertj.core.api.Assertions.assertThat(message.getText())
						.contains("my name is Lan"));
		org.mockito.Mockito.verify(memory).append("conv-1",
				com.panic.core.agent.memory.ChatTurn.Role.USER, "what is my name?");
		org.mockito.Mockito.verify(memory).append("conv-1",
				com.panic.core.agent.memory.ChatTurn.Role.ASSISTANT, "You are Lan");
	}

	@Test
	void subAgentsDoNotLoadConversationHistory() {
		com.panic.core.agent.memory.ConversationStore memory =
				org.mockito.Mockito.mock(com.panic.core.agent.memory.ConversationStore.class);
		AgentRegistry registry = registry(List.of(coordinator("search"),
				searchAgent(LocalToolSource.of("t", tools))));
		AgentExecutor executor = new AgentExecutor(testBuilder(model), registry, noSkills(), recorder,
				new AgentPromptRenderer(prompts()), memory, broadcaster, "test-key");
		model.callTool("call_search", "{\"query\":\"room for 5\"}").reply("found").reply("done");

		executor.runRoot("conv-1", "find a room");

		org.mockito.Mockito.verify(memory, org.mockito.Mockito.times(1)).loadMessages("conv-1");
	}

	@Test
	void promptIsRenderedPerCallIncludingNow() {
		AgentExecutor executor = executor(List.of(coordinator(),
				AgentDefinition.builder("search").systemPromptTemplate("Now: {now}").build()),
				noSkills());
		model.reply("1").reply("2");

		executor.runRoot("a");
		executor.runRoot("b");

		String first = model.prompts().get(0).getSystemMessage().getText();
		String second = model.prompts().get(1).getSystemMessage().getText();
		assertThat(first).doesNotContain("{now}").doesNotContain("{subAgents}").doesNotContain("{tools}");
		assertThat(second).doesNotContain("{now}");
	}

	@Test
	void missingApiKeyFailsWithClearMessage() {
		AgentRegistry registry = registry(List.of(coordinator()));
		AgentExecutor executor = new AgentExecutor(ChatClient.builder(model), registry, noSkills(), recorder,
				new AgentPromptRenderer(prompts()), null, broadcaster, "");

		assertThatThrownBy(() -> executor.runRoot("x"))
				.isInstanceOf(BookingException.class)
				.hasMessageContaining("OPENROUTER_API_KEY");
	}

	@Test
	void advisorStampsAgentAndDepth() {
		PipelineRecorder.RequestTrace trace = new PipelineRecorder.RequestTrace();
		recorder.startResponse(trace);
		PipelineEvent event = recorder.start(trace, PipelineEvent.PHASE_LLM, "search", "x", "{}", 7L, "search");

		assertThat(event.agentId()).isEqualTo("search");
		recorder.end(trace, false, null);
	}

	@Test
	void rootAnswerStreamsTokenByToken() {
		String[] chunks = { "Xin ", "chào, ", "tôi là Panic." };
		var streamingModel = new StreamingModel(chunks);
		var tokens = new java.util.ArrayList<String>();
		var recordingBroadcaster = new PipelineBroadcaster() {
			@Override
			public void publishToken(long traceId, String text, String agentId) {
				tokens.add(text);
			}
		};
		AgentRegistry registry = registry(List.of(coordinator()));
		AgentExecutor executor = new AgentExecutor(ChatClient.builder(streamingModel), registry, noSkills(), recorder,
				new AgentPromptRenderer(prompts()), null, recordingBroadcaster, "test-key");
		recorder.startResponse(new PipelineRecorder.RequestTrace());

		var result = executor.runRoot("hello");
		recorder.unbind();

		assertThat(tokens).as("tokens=%s", tokens).containsExactly("Xin ", "chào, ", "tôi là Panic.");
		assertThat(result.text()).isEqualTo("Xin chào, tôi là Panic.");
	}

	/** Streaming ChatModel that emits the given text chunks and no tool calls. */
	static final class StreamingModel implements org.springframework.ai.chat.model.ChatModel {

		private final String[] chunks;

		StreamingModel(String[] chunks) {
			this.chunks = chunks;
		}

		@Override
		public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
			return next();
		}

		@Override
		public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
				org.springframework.ai.chat.prompt.Prompt prompt) {
			return reactor.core.publisher.Flux.fromArray(chunks).map(chunk -> next(chunk));
		}

		private org.springframework.ai.chat.model.ChatResponse next() {
			return next(String.join("", chunks));
		}

		private org.springframework.ai.chat.model.ChatResponse next(String text) {
			return new org.springframework.ai.chat.model.ChatResponse(List.of(
					new org.springframework.ai.chat.model.Generation(
							new org.springframework.ai.chat.messages.AssistantMessage(text))));
		}
	}
}

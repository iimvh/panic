package com.panic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panic.core.agent.chat.ChatAccessGuard;
import com.panic.core.agent.memory.ChatTurn;
import com.panic.core.agent.memory.ConversationStore;
import com.panic.core.agent.runtime.AgentExecutor;
import com.panic.core.agent.spi.AgentResult;
import com.panic.core.redis.InMemoryStringRedisOps;
import com.panic.core.redis.StringRedisOps;
import com.panic.exception.BookingException;
import com.panic.exception.GlobalExceptionHandler;
import com.panic.pipeline.PipelineBroadcaster;
import com.panic.pipeline.PipelineEvent;
import com.panic.pipeline.PipelineRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Wire contract of {@code POST /api/agent/chat}: the raw AG-UI event sequence a client sees for
 * plain streaming text (RUN_STARTED → TOOL_DATA trio → RUN_FINISHED), tool and subagent runs,
 * and failed runs (RUN_ERROR). The executor is scripted so a run answers deterministically
 * without any LLM; the guard state rides the in-memory fake of the Redis port.
 */
@SpringBootTest(classes = AgentChatAgUiContractTest.TestBoot.class,
		webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:agent-chat-agui;DB_CLOSE_DELAY=-1",
				"spring.jpa.hibernate.ddl-auto=create-drop",
				"spring.jpa.show-sql=false",
				"spring.ai.openai.api-key=test-placeholder-api-key",
				"spring.ai.mcp.client.enabled=false",
				"app.chat.rate-limit-per-minute=20",
				"app.chat.create-limit-per-minute=20",
				"app.chat.lock-ttl-seconds=300" })
@AutoConfigureMockMvc
class AgentChatAgUiContractTest {

	/** What the scripted executor does for the current test (JUnit runs suites sequentially). */
	private static final AtomicReference<Scenario> SCENARIO = new AtomicReference<>(Scenario.PLAIN_ANSWER);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private ConversationStore store;

	@Autowired
	private ChatAccessGuard guard;

	@Autowired
	private StringRedisOps redis;

	@BeforeEach
	void startClean() {
		((InMemoryStringRedisOps) redis).advance(java.time.Duration.ofMinutes(10));
		SCENARIO.set(Scenario.PLAIN_ANSWER);
	}

	@Test
	void plainAnswerRunsRunStartTextTrioRunFinished() throws Exception {
		List<JsonNode> events = chat("find a meeting room");

		assertThat(eventTypes(events)).containsExactly("RUN_STARTED", "CUSTOM", "CUSTOM",
				"TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT",
				"CUSTOM", "TEXT_MESSAGE_END", "RUN_FINISHED");

		JsonNode started = events.get(0);
		assertThat(started.path("threadId").asText()).isEqualTo(conversationId);

		// Both TEXT deltas carry the same assistant message; the deltas read back as the answer.
		assertThat(events.get(3).path("messageId").asText()).isEqualTo(events.get(4).path("messageId").asText());
		String answer = events.get(4).path("delta").asText() + events.get(5).path("delta").asText();
		assertThat(answer).isEqualTo("Xin chào Panic");

		JsonNode finished = events.get(events.size() - 1);
		assertThat(finished.path("runId").asLong()).isEqualTo(started.path("runId").asLong());
		assertThat(finished.path("metadata").path("durationMs").isNumber()).isTrue();
	}

	@Test
	void toolRunDrawsTheCallQuadrupleBeforeRunFinished() throws Exception {
		SCENARIO.set(Scenario.TOOL_RUN);
		List<JsonNode> events = chat("book sala A");

		assertThat(eventTypes(events)).containsExactly(
				"RUN_STARTED", "CUSTOM", "CUSTOM",
				"TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END", "TOOL_CALL_RESULT",
				"CUSTOM", "RUN_FINISHED");

		String toolCallId = events.get(3).path("toolCallId").asText();
		assertThat(events.get(4).path("toolCallId").asText()).isEqualTo(toolCallId);
		assertThat(events.get(5).path("toolCallId").asText()).isEqualTo(toolCallId);
		assertThat(events.get(3).path("toolCallName").asText()).isEqualTo("room-lookup");
		assertThat(events.get(6).path("role").asText()).isEqualTo("tool");
		assertThat(events.get(6).path("content").asText()).isEqualTo("{\"roomId\":\"A\"}");
	}

	@Test
	void subagentRunAnchorsTheNestedWorkOnItsToolCall() throws Exception {
		SCENARIO.set(Scenario.SUBAGENT_RUN);
		List<JsonNode> events = chat("who is free at 3pm");

		assertThat(eventTypes(events)).containsExactly(
				"RUN_STARTED", "CUSTOM", "CUSTOM",
				"SUBAGENT_STARTED",
				"TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END", "TOOL_CALL_RESULT",
				"SUBAGENT_FINISHED",
				"CUSTOM", "RUN_FINISHED");

		assertThat(events.get(3).path("name").asText()).isEqualTo("room_worker");
		String toolCallId = events.get(4).path("toolCallId").asText();
		assertThat(events.get(3).path("parentToolCallId").asText()).isEqualTo(toolCallId);
		// The call rides the parent-owned assistant message, so it stays in the parent
		// lane: a tool call belongs to the message that carries it. The subagent run is
		// anchored to the call by SUBAGENT_* and carries the same id start to finish.
		assertThat(events.get(4).has("subagentRunId")).isFalse();
		assertThat(events.get(3).path("subagentRunId").asText())
				.isEqualTo(events.get(8).path("subagentRunId").asText());
		assertThat(events.get(4).path("parentMessageId").asText()).isEqualTo("m-" + conversationSendRunId(events));
		assertThat(events.get(8).path("result").asText()).isEqualTo("{\"roomId\":\"A\"}");
	}

	/** The runId the single chat run carried, echoed back by its terminal event. */
	private static String conversationSendRunId(List<JsonNode> events) {
		return events.get(events.size() - 1).path("runId").asText();
	}

	/** Text of a replayed message: plain string for user turns, joined text parts for assistant turns. */
	private static String replayText(JsonNode message) {
		JsonNode content = message.path("content");
		if (content.isTextual()) {
			return content.asText();
		}
		StringBuilder text = new StringBuilder();
		for (JsonNode part : content) {
			if ("text".equals(part.path("type").asText())) {
				text.append(part.path("text").asText());
			}
		}
		return text.toString();
	}

	@Test
	void failedRunClosesWithRunErrorOnly() throws Exception {
		SCENARIO.set(Scenario.FAILED_RUN);
		List<JsonNode> events = chat("break everything");

		assertThat(eventTypes(events)).containsExactly("RUN_STARTED", "CUSTOM", "CUSTOM", "CUSTOM", "CUSTOM",
				"RUN_ERROR");
		assertThat(events.get(5).path("message").asText()).isEqualTo("provider offline");
		assertThat(events.get(5).path("code").asText()).isEqualTo("guard_rejected");

		// The failed task keeps its question, without an assistant partner, and the next run works.
		assertThat(store.session(conversationId).turns()).hasSize(1);
		assertThat(store.session(conversationId).turns().get(0).status()).isEqualTo("error");

		SCENARIO.set(Scenario.PLAIN_ANSWER);
		List<JsonNode> retry = chatIn(conversationId, "try again later");
		assertThat(eventTypes(retry).get(0)).isEqualTo("RUN_STARTED");
		assertThat(eventTypes(retry).get(eventTypes(retry).size() - 1)).isEqualTo("RUN_FINISHED");

		// Both dialogue turns replay: the failed exchange plus the successful retry.
		var replay = mvc.perform(get("/api/conversations/" + conversationId))
				.andExpect(status().isOk()).andReturn();
		JsonNode session = json.readTree(new String(replay.getResponse().getContentAsByteArray(),
				java.nio.charset.StandardCharsets.UTF_8));
		JsonNode replayMessages = session.path("messages");

		assertThat(replayMessages.size()).isEqualTo(4);
		// The failed exchange replays without an assistant partner; only the retry gets an answer.
		assertThat(replayMessages.get(2).path("content").asText()).isEqualTo("try again later");
		assertThat(replayText(replayMessages.get(3))).isEqualTo("Xin chào Panic");
		assertThat(session.path("turns").size()).isEqualTo(2);
		assertThat(session.path("turns").get(0).path("status").asText()).isEqualTo("error");
		assertThat(session.path("turns").get(1).path("status").asText()).isEqualTo("ok");
	}

	@Test
	void lockedConversationAnswers409BeforeAnyFrame() throws Exception {
		String id = store.create("local");
		guard.acquireLock(id);
		try {
			mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
					.content(runInput(id, "hi"))).andExpect(status().isConflict())
					.andExpect(jsonPath("$.error").isNotEmpty());
		} finally {
			guard.releaseChat(id);
		}
	}

	private String conversationId;

	private List<JsonNode> chat(String message) throws Exception {
		conversationId = store.create("local");
		return chatIn(conversationId, message);
	}

	// A re-run continues the same conversation: the retry lands in its thread.
	private List<JsonNode> chatIn(String threadId, String message) throws Exception {
		MvcResult pending = mvc
				.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
						.content(runInput(threadId, message)))
				.andExpect(request().asyncStarted())
				.andReturn();
		MvcResult dispatched = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
		String body = new String(dispatched.getResponse().getContentAsByteArray(),
				java.nio.charset.StandardCharsets.UTF_8);

		List<JsonNode> events = new ArrayList<>();
		for (String chunk : body.split("\n\n")) {
			if (chunk.startsWith("data:")) {
				events.add(json.readTree(chunk.substring("data:".length())));
			}
		}
		assertThat(events).as("every SSE chunk carries exactly one protocol event").hasSizeGreaterThan(0);
		return events;
	}

	private static String runInput(String threadId, String message) {
		return "{\"threadId\":\"" + threadId + "\",\"messages\":[{\"id\":\"u1\",\"role\":\"user\",\"content\":"
				+ "\"" + message + "\"}]}";
	}

	private static List<String> eventTypes(List<JsonNode> events) {
		return events.stream().map(event -> event.path("type").asText()).toList();
	}

	/** Executor shaped per scenario: same trace plumbing the domain, no LLM involved. */
	static final class ScriptedAgentExecutor extends AgentExecutor {

		private final PipelineRecorder recorder;
		private final PipelineBroadcaster broadcaster;
		private final ConversationStore memory;

		ScriptedAgentExecutor(PipelineRecorder recorder, PipelineBroadcaster broadcaster,
				ConversationStore memory) {
			super(null, null, null, recorder, null, memory, broadcaster);
			this.recorder = recorder;
			this.broadcaster = broadcaster;
			this.memory = memory;
		}

		@Override
		public AgentResult runRoot(String conversationId, String userMessage) {
			PipelineRecorder.RequestTrace trace = recorder.currentTrace();
			long traceId = trace == null ? 0 : trace.id();
			switch (SCENARIO.get()) {
				case TOOL_RUN -> {
					PipelineEvent call = recorder.start(trace, PipelineEvent.PHASE_TOOL, "room-lookup",
							"Room search", "{}");
					recorder.finish(trace, call, PipelineEvent.STATUS_OK, "Room search", "{\"roomId\":\"A\"}",
							12L);
					recordTurns(conversationId, userMessage, "Room A booked", traceId);
					return AgentResult.ok("coordinator", "Room A booked", 0);
				}
				case SUBAGENT_RUN -> {
					PipelineEvent call = recorder.start(trace, PipelineEvent.PHASE_TOOL, "room-lookup",
							"Room search", "{}", 1L, "room_worker");
					recorder.finish(trace, call, PipelineEvent.STATUS_OK, "Room search", "{\"roomId\":\"A\"}",
							12L);
					recordTurns(conversationId, userMessage, "Room A booked", traceId);
					return AgentResult.ok("coordinator", "Room A booked", 0);
				}
				case FAILED_RUN -> {
					recordTurns(conversationId, userMessage, null, traceId);
					throw new BookingException("provider offline");
				}
				default -> {
					broadcaster.publishToken(traceId, "Xin ", "coordinator");
					broadcaster.publishToken(traceId, "chào Panic", "coordinator");
					recordTurns(conversationId, userMessage, "Xin chào Panic", traceId);
					return AgentResult.ok("coordinator", "Xin chào Panic", 0);
				}
			}
		}

		/** Same memory contract the real executor enforces: every run leaves its turns behind. */
		private void recordTurns(String conversationId, String userMessage, String answer, long traceId) {
			memory.append(conversationId, ChatTurn.Role.USER, userMessage, traceId);
			if (answer != null) {
				memory.append(conversationId, ChatTurn.Role.ASSISTANT, answer, traceId);
			}
		}
	}

	private enum Scenario {
		PLAIN_ANSWER, TOOL_RUN, SUBAGENT_RUN, FAILED_RUN
	}

	@SpringBootConfiguration
	@AutoConfigurationPackage(basePackages = "com.panic")
	@EnableAutoConfiguration
	@Import({ com.panic.controller.AgentController.class, com.panic.controller.ConversationController.class,
			ConversationStore.class, ChatAccessGuard.class,
			GlobalExceptionHandler.class, PipelineRecorder.class, PipelineBroadcaster.class })
	static class TestBoot {

		@Bean
		StringRedisOps stringRedisOps() {
			return new InMemoryStringRedisOps();
		}

		@Bean
		AgentExecutor agentExecutor(PipelineRecorder recorder, PipelineBroadcaster broadcaster,
				ConversationStore memory) {
			return new ScriptedAgentExecutor(recorder, broadcaster, memory);
		}
	}
}

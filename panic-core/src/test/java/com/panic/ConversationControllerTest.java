package com.panic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panic.core.agent.chat.ChatAccessGuard;
import com.panic.core.agent.memory.ConversationRepository;
import com.panic.core.agent.memory.ConversationStore;
import com.panic.core.redis.InMemoryStringRedisOps;
import com.panic.core.redis.StringRedisOps;
import com.panic.pipeline.PipelineEvent;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP boundary of {@code /api/conversations}: the status-code contract (201/404/409/429) and
 * the list/replay JSON shape. The access guard runs against the in-memory fake of the Redis
 * port, so the suite passes without any Redis — quotas and locks still answer like production.
 */
@SpringBootTest(classes = ConversationControllerTest.TestBoot.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:conv-controller;DB_CLOSE_DELAY=-1",
				"spring.jpa.hibernate.ddl-auto=create-drop",
				"spring.jpa.show-sql=false",
				"spring.ai.openai.api-key=missing-openrouter-api-key",
				"spring.ai.mcp.client.enabled=false",
				"app.chat.rate-limit-per-minute=20",
				"app.chat.create-limit-per-minute=2",
				"app.chat.lock-ttl-seconds=300" })
@AutoConfigureMockMvc
class ConversationControllerTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private ConversationStore store;

	@Autowired
	private ConversationRepository conversations;

	@Autowired
	private ChatAccessGuard guard;

	@Autowired
	private StringRedisOps redis;

	@BeforeEach
	void expireGuardsFromEarlierTests() {
		// Quota windows and locks live 60s/300s; expired keys behave as absent, so every test
		// starts with clean guard state without rebuilding the whole context.
		((InMemoryStringRedisOps) redis).advance(java.time.Duration.ofMinutes(10));
	}

	@Test
	void createdConversationAnswers201WithId() throws Exception {
		MvcResult result = mvc.perform(post("/api/conversations")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andReturn();

		String id = json.readTree(result.getResponse().getContentAsString()).path("id").asText();
		assertThat(store.exists(id)).as("created session is persisted").isTrue();
	}

	@Test
	void listShowsNewestActivityFirst() throws Exception {
		String older = store.create("local");
		String newer = store.create("local");
		String busiest = store.create("local");
		// Explicit future timestamps: ordering is deterministic and fresh rows always outrank
		// the rows earlier tests left in the shared test context.
		activity(older, LocalDateTime.now().plusHours(1));
		activity(newer, LocalDateTime.now().plusHours(2));
		activity(busiest, LocalDateTime.now().plusHours(3));

		MvcResult result = mvc.perform(get("/api/conversations")).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(busiest))
				.andExpect(jsonPath("$[0].lastActivityAt").isNumber())
				.andReturn();

		JsonNode rows = json.readTree(result.getResponse().getContentAsString());
		assertThat(rows.get(1).path("id").asText()).isEqualTo(newer);
		assertThat(rows.get(2).path("id").asText()).isEqualTo(older);
	}

	@Test
	void renameAnswersAndPersistsTheNewTitle() throws Exception {
		String id = createdViaApi();

		mvc.perform(patch("/api/conversations/" + id)
						.contentType("application/json")
						.content("{\"title\":\"daily standup\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("daily standup"));

		assertThat(store.session(id).title()).isEqualTo("daily standup");

		mvc.perform(patch("/api/conversations/" + id)
						.contentType("application/json")
						.content("{\"title\":\"   \"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void replayReturnsTurnsWithTheirRecordedPipeline() throws Exception {
		String id = store.create("local");
		store.append(id, com.panic.core.agent.memory.ChatTurn.Role.USER, "find a room", 90L);
		store.append(id, com.panic.core.agent.memory.ChatTurn.Role.ASSISTANT, "Sala A is free", 90L);
		store.recordTrace(id, 90L, List.of(
				new PipelineEvent(1, 90L, "llm", "coordinator", "ok", "Model call",
						"{}", 1_000L, 12L, 1L, "coordinator", 0, "assistant"),
				new PipelineEvent(2, 90L, "tool", "coordinator", "ok", "Room search",
						"{}", 2_000L, 34L, 2L, "coordinator", 0, "tool")));

		mvc.perform(get("/api/conversations/" + id)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.title").value("find a room"))
				.andExpect(jsonPath("$.turns[0].task").value("find a room"))
				.andExpect(jsonPath("$.turns[0].answer").value("Sala A is free"))
				.andExpect(jsonPath("$.turns[0].status").value("ok"))
				.andExpect(jsonPath("$.turns[0].at").isNumber())
				.andExpect(jsonPath("$.turns[0].pipeline[0].title").value("Model call"))
				.andExpect(jsonPath("$.turns[0].pipeline[1].title").value("Room search"));
	}

	@Test
	void replayAssistantMessageCarriesToolCallPartsAndFinalText() throws Exception {
		String id = store.create("local");
		store.append(id, com.panic.core.agent.memory.ChatTurn.Role.USER, "hôm nay có tin gì?", 91L);
		store.append(id, com.panic.core.agent.memory.ChatTurn.Role.ASSISTANT, "Có 3 tin mới", 91L);
		store.recordTrace(id, 91L, List.of(
				// Tool call recorded twice with the same id: running (with args), then finished.
				new PipelineEvent(5, 91L, "tool", "coordinator", "running", "Sub-agent digest",
						"{\"args\":{\"query\":\"hôm nay có tin gì?\"}}", 1_000L, null, 0L, "coordinator", 0, "tool"),
				new PipelineEvent(5, 91L, "tool", "coordinator", "ok", "Sub-agent digest",
						"{\"output\":\"Có 3 tin mới\"}", 1_000L, 12L, 0L, "coordinator", 0, "tool"),
				// A tool-source listing event is NOT a tool call and must not become a part.
				new PipelineEvent(6, 91L, "tool", "digest", "ok", "Tool source digest-tools (LOCAL)",
						"{\"provider\":\"LOCAL\",\"tools\":[\"today\"]}", 1_100L, 1L, 5L, "digest", 1, "tool")));

		mvc.perform(get("/api/conversations/" + id)).andExpect(status().isOk())
				// Assistant content is an array of parts: one tool-call, then the final text.
				.andExpect(jsonPath("$.messages[1].role").value("assistant"))
				.andExpect(jsonPath("$.messages[1].content[0].type").value("tool-call"))
				.andExpect(jsonPath("$.messages[1].content[0].toolName").value("digest"))
				.andExpect(jsonPath("$.messages[1].content[0].args.query").value("hôm nay có tin gì?"))
				.andExpect(jsonPath("$.messages[1].content[0].result").value("Có 3 tin mới"))
				.andExpect(jsonPath("$.messages[1].content[?(@.type=='tool-call')]").value(org.hamcrest.Matchers.hasSize(1)))
				.andExpect(jsonPath("$.messages[1].content[?(@.type=='text')].text").value(org.hamcrest.Matchers.contains("Có 3 tin mới")));
	}

	@Test
	void unknownConversationAnswers404Everywhere() throws Exception {
		mvc.perform(get("/api/conversations/nope")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").isNotEmpty());
		mvc.perform(patch("/api/conversations/nope")
						.contentType("application/json")
						.content("{\"title\":\"x\"}"))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/conversations/nope")).andExpect(status().isNotFound());
	}

	@Test
	void deleteAnswers204AndRemovesTheConversation() throws Exception {
		String id = createdViaApi();

		mvc.perform(delete("/api/conversations/" + id)).andExpect(status().isNoContent());

		assertThat(store.exists(id)).isFalse();
		mvc.perform(get("/api/conversations/" + id)).andExpect(status().isNotFound());
	}

	@Test
	void creatingBeyondThePerMinuteQuotaAnswers429() throws Exception {
		// app.chat.create-limit-per-minute=2 in this suite.
		mvc.perform(post("/api/conversations")).andExpect(status().isCreated());
		mvc.perform(post("/api/conversations")).andExpect(status().isCreated());
		mvc.perform(post("/api/conversations")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error").isNotEmpty());
	}

	@Test
	void runningConversationIsNotRenamableOrDeletable() throws Exception {
		String id = createdViaApi();
		// The lock the chat endpoint acquires while a task drives the conversation.
		guard.acquireLock(id);

		mvc.perform(patch("/api/conversations/" + id)
						.contentType("application/json")
						.content("{\"title\":\"nope\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").isNotEmpty());
		mvc.perform(delete("/api/conversations/" + id)).andExpect(status().isConflict());

		guard.releaseChat(id);
		mvc.perform(delete("/api/conversations/" + id)).andExpect(status().isNoContent());
	}

	private String createdViaApi() throws Exception {
		return json.readTree(mvc.perform(post("/api/conversations"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString()).path("id").asText();
	}

	private void activity(String conversationId, LocalDateTime at) {
		var conversation = conversations.findById(conversationId).orElseThrow();
		conversation.setLastActivityAt(at);
		conversations.save(conversation);
	}

	@SpringBootConfiguration
	@AutoConfigurationPackage(basePackages = "com.panic")
	@EnableAutoConfiguration
	@Import({ com.panic.controller.ConversationController.class, ConversationStore.class,
			ChatAccessGuard.class, com.panic.exception.GlobalExceptionHandler.class })
	static class TestBoot {

		@Bean
		StringRedisOps stringRedisOps() {
			return new InMemoryStringRedisOps();
		}
	}
}

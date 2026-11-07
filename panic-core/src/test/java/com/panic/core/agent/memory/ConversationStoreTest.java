package com.panic.core.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.panic.core.redis.InMemoryStringRedisOps;
import com.panic.pipeline.PipelineEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(ConversationStoreTest.TestConfig.class)
class ConversationStoreTest {

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class TestConfig {
	}

	@Autowired
	private ConversationRepository conversations;

	@Autowired
	private ChatTurnRepository turns;

	@Autowired
	private TraceEventRepository traceEvents;

	private ConversationStore store;

	@BeforeEach
	void setUp() {
		store = new ConversationStore(conversations, turns, traceEvents, 3);
	}

	@Test
	void newConversationExists() {
		String id = store.create();
		assertThat(store.exists(id)).isTrue();
		assertThat(store.exists("nope")).isFalse();
		assertThat(store.exists(null)).isFalse();
	}

	@Test
	void appendAndLoadInOrder() {
		String id = store.create();
		store.append(id, ChatTurn.Role.USER, "hello");
		store.append(id, ChatTurn.Role.ASSISTANT, "hi there");

		var messages = store.loadMessages(id);
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
		assertThat(messages.get(0).getText()).isEqualTo("hello");
		assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
		assertThat(messages.get(1).getText()).isEqualTo("hi there");
	}

	@Test
	void windowKeepsOnlyMostRecentTurns() {
		String id = store.create();
		for (int i = 1; i <= 5; i++) {
			store.append(id, ChatTurn.Role.USER, "u" + i);
		}

		var messages = store.loadMessages(id);
		assertThat(messages).extracting(m -> m.getText()).containsExactly("u3", "u4", "u5");
	}

	@Test
	void appendToUnknownConversationIsIgnored() {
		store.append("missing", ChatTurn.Role.USER, "x");
		assertThat(store.loadMessages("missing")).isEmpty();
	}

	@Test
	void unknownConversationHasNoMessages() {
		assertThat(store.loadMessages("nope")).isEmpty();
	}

	@Test
	void listOrdersConversationsByLatestActivity() {
		String older = store.create("local");
		String newer = store.create("local");
		String busiest = store.create("local");
		// Explicit timestamps so ordering is deterministic, not a race with LocalDateTime.now().
		activity(older, LocalDateTime.now().minusHours(3));
		activity(newer, LocalDateTime.now().minusHours(2));
		activity(busiest, LocalDateTime.now().minusHours(1));

		var summaries = store.list("local").stream().map(ConversationStore.SessionSummary::id).toList();

		assertThat(summaries).containsExactly(busiest, newer, older);
	}

	@Test
	void appendingBumpsLastActivity() {
		String id = store.create("local");
		String other = store.create("local");
		activity(id, LocalDateTime.now().minusHours(5));
		activity(other, LocalDateTime.now().minusHours(1));

		store.append(id, ChatTurn.Role.USER, "hello again");

		assertThat(store.list("local").stream().map(ConversationStore.SessionSummary::id).toList())
				.containsExactly(id, other);
	}

	@Test
	void appendSeedsTitleFromTheFirstUserMessage() {
		String id = store.create("local");
		store.append(id, ChatTurn.Role.ASSISTANT, "assistant note");
		store.append(id, ChatTurn.Role.USER, "  book a room\nfor tomorrow please  ");

		var summary = store.list("local").stream()
				.filter(s -> s.id().equals(id))
				.findFirst().orElseThrow();
		assertThat(summary.title()).isEqualTo("book a room");

		// A later user message must not overwrite the seeded title.
		store.append(id, ChatTurn.Role.USER, "another question entirely");
		summary = store.list("local").stream()
				.filter(s -> s.id().equals(id))
				.findFirst().orElseThrow();
		assertThat(summary.title()).isEqualTo("book a room");
	}

	@Test
	void longFirstMessagesAreCroppedToTheTitleColumn() {
		String id = store.create("local");
		store.append(id, ChatTurn.Role.USER, "x".repeat(500));

		var summary = store.list("local").stream()
				.filter(s -> s.id().equals(id))
				.findFirst().orElseThrow();
		assertThat(summary.title()).hasSizeLessThanOrEqualTo(120);
	}

	@Test
	void renameChangesTheTitleAndKeepsEverythingElse() {
		String id = store.create("local");
		store.append(id, ChatTurn.Role.USER, "first question");

		ConversationStore.SessionSummary renamed = store.rename(id, "daily standup");

		assertThat(renamed.title()).isEqualTo("daily standup");
		assertThat(store.session(id).title()).isEqualTo("daily standup");
		assertThat(store.rename("nope", "x")).isNull();
	}

	@Test
	void deleteRemovesTheConversationWithItsTurnsAndTraces() {
		String id = store.create("local");
		store.append(id, ChatTurn.Role.USER, "question", 42L);
		store.append(id, ChatTurn.Role.ASSISTANT, "answer", 42L);
		store.recordTrace(id, 42L, List.of(traceEvent(1, 42L)));

		assertThat(store.delete(id)).isTrue();

		assertThat(store.exists(id)).isFalse();
		assertThat(store.session(id)).isNull();
		assertThat(turns.findByConversation_IdOrderByIdAsc(id)).isEmpty();
		assertThat(traceEvents.findByConversationIdOrderByTraceIdAscSequenceAsc(id)).isEmpty();
		assertThat(store.delete(id)).isFalse();
	}

	@Test
	void sessionReplaysWholeTurnsWithTheirPipelines() {
		String id = store.create("local");
		// A completed turn: user + assistant share the trace, whose pipeline was recorded.
		store.append(id, ChatTurn.Role.USER, "find a room", 100L);
		store.append(id, ChatTurn.Role.ASSISTANT, "Sala A is free", 100L);
		store.recordTrace(id, 100L, List.of(traceEvent(1, 100L), traceEvent(2, 100L)));
		// A failed turn: the user question was recorded, the answer never arrived.
		store.append(id, ChatTurn.Role.USER, "book it now", 101L);
		store.recordTrace(id, 101L, List.of(traceEvent(1, 101L)));
		// Pre-trace turns (legacy rows): pairing falls back to adjacency.
		store.append(id, ChatTurn.Role.USER, "old question");
		store.append(id, ChatTurn.Role.ASSISTANT, "old answer");

		ConversationStore.ConversationSession session = store.session(id);

		assertThat(session.id()).isEqualTo(id);
		assertThat(session.title()).isEqualTo("find a room");
		assertThat(session.turns()).hasSize(3);

		var first = session.turns().get(0);
		assertThat(first.task()).isEqualTo("find a room");
		assertThat(first.answer()).isEqualTo("Sala A is free");
		assertThat(first.status()).isEqualTo("ok");
		assertThat(first.at()).isPositive();
		assertThat(first.pipeline()).hasSize(2);
		assertThat(first.pipeline().get(0).traceId()).isEqualTo(100L);

		var failed = session.turns().get(1);
		assertThat(failed.task()).isEqualTo("book it now");
		assertThat(failed.answer()).isEmpty();
		assertThat(failed.status()).isEqualTo("error");
		assertThat(failed.pipeline()).hasSize(1);

		var legacy = session.turns().get(2);
		assertThat(legacy.task()).isEqualTo("old question");
		assertThat(legacy.status()).isEqualTo("ok");
		assertThat(legacy.pipeline()).isEmpty();
	}

	@Test
	void recordTraceIsIdempotentPerRun() {
		String id = store.create("local");
		store.append(id, ChatTurn.Role.USER, "q", 7L);

		store.recordTrace(id, 7L, List.of(traceEvent(1, 7L)));
		store.recordTrace(id, 7L, List.of(traceEvent(1, 7L), traceEvent(2, 7L)));

		assertThat(store.session(id).turns().get(0).pipeline()).hasSize(1);
		assertThat(traceEvents.count()).isEqualTo(1);
	}

	@Test
	void windowReadsThroughTheCacheStayIdenticalToJpa() {
		ConversationStore cached = new ConversationStore(conversations, turns, traceEvents, 3,
				new ConversationCache(new InMemoryStringRedisOps(), 600));
		String id = store.create("local");
		for (int i = 1; i <= 5; i++) {
			store.append(id, ChatTurn.Role.USER, "u" + i);
		}

		// First read populates the window cache, the second one is served from it.
		List<String> first = texts(cached.loadMessages(id));
		List<String> second = texts(cached.loadMessages(id));

		assertThat(first).containsExactly("u3", "u4", "u5");
		assertThat(second).containsExactly("u3", "u4", "u5");

		// A write through the same store keeps the cached window in sync with the new turn.
		cached.append(id, ChatTurn.Role.USER, "u6");
		assertThat(texts(cached.loadMessages(id))).containsExactly("u4", "u5", "u6");
	}

	private static List<String> texts(List<org.springframework.ai.chat.messages.Message> messages) {
		return messages.stream().map(m -> m.getText()).toList();
	}

	private static PipelineEvent traceEvent(long eventId, long traceId) {
		return new PipelineEvent(eventId, traceId, "llm", "coordinator", "ok",
				"Model call " + eventId, "{}", 7_000L + eventId, 12L, eventId, "coordinator", 0, "assistant");
	}

	/** Deterministic lastActivityAt for ordering tests. */
	private void activity(String conversationId, LocalDateTime at) {
		Conversation conversation = conversations.findById(conversationId).orElseThrow();
		conversation.setLastActivityAt(at);
		conversations.save(conversation);
	}
}

package com.panic.core.agent.memory;

import com.panic.pipeline.PipelineEvent;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the whole record of a conversation: its turns (user/assistant messages) and the
 * pipeline trace of every turn, so sessions replay from any device straight from the store.
 * The Redis-backed {@link ConversationCache} accelerates the recent-turn window the root
 * agent reads; JPA stays the source of truth and every path works without it.
 */
@Service
public class ConversationStore {

	private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

	private static final int TITLE_MAX = 120;

	private final ConversationRepository conversations;
	private final ChatTurnRepository turns;
	private final TraceEventRepository traceEvents;
	private final int windowTurns;
	private final ConversationCache cache;

	@Autowired
	public ConversationStore(ConversationRepository conversations, ChatTurnRepository turns,
			TraceEventRepository traceEvents, @Value("${app.memory.window-turns:20}") int windowTurns,
			@Nullable ConversationCache cache) {
		this.conversations = conversations;
		this.turns = turns;
		this.traceEvents = traceEvents;
		this.windowTurns = windowTurns <= 0 ? 20 : windowTurns;
		this.cache = cache;
	}

	/** Test/slice constructor: full JPA backing, no cache acceleration. */
	public ConversationStore(ConversationRepository conversations, ChatTurnRepository turns,
			TraceEventRepository traceEvents, int windowTurns) {
		this(conversations, turns, traceEvents, windowTurns, null);
	}

	@Transactional
	public String create() {
		return create(Conversation.LOCAL_USER);
	}

	/** New conversation for a user; the first user message later seeds the sidebar title. */
	@Transactional
	public String create(String userId) {
		Conversation conversation = new Conversation();
		conversation.setId(UUID.randomUUID().toString());
		conversation.setUserId(userId == null || userId.isBlank() ? Conversation.LOCAL_USER : userId);
		conversations.save(conversation);
		return conversation.getId();
	}

	public boolean exists(String conversationId) {
		return conversationId != null && !conversationId.isBlank()
				&& conversations.existsById(conversationId);
	}

	/** All conversations of a user, most recently active first (sidebar order). */
	@Transactional(readOnly = true)
	public List<SessionSummary> list(String userId) {
		return conversations.findByUserIdOrderByLastActivityAtDesc(userId).stream()
				.map(ConversationStore::summaryOf)
				.toList();
	}

	/** Whole-session replay: every paired turn with its recorded pipeline trace; null when unknown. */
	@Transactional(readOnly = true)
	public ConversationSession session(String conversationId) {
		Conversation conversation = conversations.findById(conversationId).orElse(null);
		if (conversation == null) {
			return null;
		}
		Map<Long, List<PipelineEvent>> pipelinesByTrace = traceEvents == null ? Map.of()
				: traceEvents.findByConversationIdOrderByTraceIdAscSequenceAsc(conversationId).stream()
						.map(ConversationStore::toPipelineEvent)
						.collect(Collectors.groupingBy(PipelineEvent::traceId, LinkedHashMap::new,
								Collectors.toList()));
		return new ConversationSession(conversation.getId(), conversation.getTitle(),
				replayTurns(conversationId, turns.findByConversation_IdOrderByIdAsc(conversationId),
						pipelinesByTrace));
	}

	/** Renames (or seeds) a conversation; returns the updated summary or null when unknown. */
	@Transactional
	public SessionSummary rename(String conversationId, String title) {
		Conversation conversation = conversations.findById(conversationId).orElse(null);
		if (conversation == null) {
			return null;
		}
		conversation.setTitle(cropTitle(title));
		conversations.save(conversation);
		return summaryOf(conversation);
	}

	/** Deletes a conversation with all its turns and traces; false when the id is unknown. */
	@Transactional
	public boolean delete(String conversationId) {
		if (!exists(conversationId)) {
			return false;
		}
		turns.deleteByConversation_Id(conversationId);
		if (traceEvents != null) {
			traceEvents.deleteByConversationId(conversationId);
		}
		conversations.deleteById(conversationId);
		if (cache != null) {
			cache.invalidate(conversationId);
		}
		return true;
	}

	/** Older to newer, capped to the configured window (most recent turns win). */
	@Transactional(readOnly = true)
	public List<Message> loadMessages(String conversationId) {
		if (!exists(conversationId)) {
			return List.of();
		}
		List<ConversationCache.CachedTurn> window = null;
		if (cache != null) {
			Optional<List<ConversationCache.CachedTurn>> cached = cache.loadWindow(conversationId);
			if (cached.isPresent()) {
				window = cached.get();
			}
		}
		if (window == null) {
			window = windowContents(conversationId);
			if (cache != null && !window.isEmpty()) {
				cache.storeWindow(conversationId, window);
			}
		}
		List<Message> messages = new ArrayList<>(window.size());
		for (ConversationCache.CachedTurn turn : window) {
			if (turn.role() == ChatTurn.Role.USER) {
				messages.add(new UserMessage(turn.content()));
			} else {
				messages.add(new AssistantMessage(turn.content()));
			}
		}
		return messages;
	}

	/** Appends a turn without a pipeline trace (legacy callers, manual turns). */
	@Transactional
	public void append(String conversationId, ChatTurn.Role role, String content) {
		append(conversationId, role, content, null);
	}

	/**
	 * Appends one turn of a conversation: the first user message seeds the sidebar title and
	 * every append bumps {@code lastActivityAt} so the sidebar ordering follows real activity.
	 */
	@Transactional
	public void append(String conversationId, ChatTurn.Role role, String content, Long traceId) {
		if (conversationId == null || conversationId.isBlank() || content == null) {
			return;
		}
		Conversation conversation = conversations.findById(conversationId).orElse(null);
		if (conversation == null) {
			log.warn("Skipping memory append: conversation '{}' does not exist", conversationId);
			return;
		}
		if (role == ChatTurn.Role.USER && (conversation.getTitle() == null || conversation.getTitle().isBlank())) {
			conversation.setTitle(cropTitle(content));
		}
		conversation.setLastActivityAt(LocalDateTime.now());
		conversations.save(conversation);
		ChatTurn turn = new ChatTurn();
		turn.setConversation(conversation);
		turn.setRole(role);
		turn.setContent(content);
		turn.setTraceId(traceId);
		turns.save(turn);
		if (cache != null) {
			cache.storeWindow(conversationId, windowContents(conversationId));
		}
	}

	/**
	 * Persists the finished pipeline trace of one run in a single batch — the success and the
	 * error path of the chat controller both funnel here. Recording twice (replay of a lost
	 * ack) is a no-op: one trace id maps to exactly one stored timeline.
	 */
	@Transactional
	public void recordTrace(String conversationId, long traceId, List<PipelineEvent> events) {
		if (traceEvents == null || conversationId == null || conversationId.isBlank() || traceId == 0
				|| events == null || events.isEmpty() || traceEvents.existsByTraceId(traceId)) {
			return;
		}
		List<TraceEvent> rows = new ArrayList<>(events.size());
		for (int sequence = 0; sequence < events.size(); sequence++) {
			rows.add(rowOf(conversationId, traceId, sequence, events.get(sequence)));
		}
		traceEvents.saveAll(rows);
	}

	private static TraceEvent rowOf(String conversationId, long traceId, int sequence, PipelineEvent event) {
		TraceEvent row = new TraceEvent();
		row.setConversationId(conversationId);
		row.setTraceId(traceId);
		row.setSequence(sequence);
		row.setEventId(event.id());
		row.setPhase(nullToEmpty(event.phase()));
		row.setStatus(nullToEmpty(event.status()));
		row.setTitle(nullToEmpty(event.title()));
		row.setDetail(nullToEmpty(event.detail()));
		row.setStartedAt(event.startedAt());
		row.setDurationMs(event.durationMs());
		row.setParentId(event.parentId());
		row.setRole(event.role());
		row.setAgentId(event.agentId());
		row.setActor(event.actor());
		row.setDepth(event.depth() == null ? 0 : event.depth());
		return row;
	}

	private static PipelineEvent toPipelineEvent(TraceEvent row) {
		return new PipelineEvent(row.getEventId(), row.getTraceId(), row.getPhase(), row.getActor(),
				row.getStatus(), row.getTitle(), row.getDetail(), row.getStartedAt(), row.getDurationMs(),
				row.getParentId(), row.getAgentId(), row.getDepth(), row.getRole());
	}

	/**
	 * Replays whole turns: a user turn pairs with the assistant turn of the same trace (the
	 * directly following one for pre-trace rows); a user turn without its answer marks the
	 * turn as failed. Each turn carries its recorded pipeline events.
	 */
	private static List<ConversationSession.ReplayedTurn> replayTurns(String conversationId, List<ChatTurn> all,
			Map<Long, List<PipelineEvent>> pipelinesByTrace) {
		List<ConversationSession.ReplayedTurn> replayed = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			ChatTurn user = all.get(i);
			if (user.getRole() != ChatTurn.Role.USER) {
				continue;
			}
			ChatTurn next = i + 1 < all.size() ? all.get(i + 1) : null;
			boolean paired = next != null && next.getRole() == ChatTurn.Role.ASSISTANT
					&& (user.getTraceId() == null ? next.getTraceId() == null
							: user.getTraceId().equals(next.getTraceId()));
			if (paired) {
				i++;
			}
			Long traceId = user.getTraceId();
			replayed.add(new ConversationSession.ReplayedTurn(user.getContent(),
					paired ? next.getContent() : "", paired ? "ok" : "error",
					toEpochMillis(user.getCreatedAt()),
					traceId == null ? List.of() : pipelinesByTrace.getOrDefault(traceId, List.of())));
		}
		return replayed;
	}

	private List<ConversationCache.CachedTurn> windowContents(String conversationId) {
		List<ChatTurn> recent = turns.findByConversation_IdOrderByIdDesc(conversationId,
				PageRequest.of(0, windowTurns));
		List<ChatTurn> ordered = new ArrayList<>(recent);
		java.util.Collections.reverse(ordered);
		return ordered.stream()
				.map(turn -> new ConversationCache.CachedTurn(turn.getRole(), turn.getContent()))
				.toList();
	}

	private static SessionSummary summaryOf(Conversation conversation) {
		return new SessionSummary(conversation.getId(), conversation.getTitle(),
				toEpochMillis(conversation.getLastActivityAt()));
	}

	private static String cropTitle(String content) {
		String firstLine = content == null ? "" : content.strip();
		int newline = firstLine.indexOf('\n');
		if (newline > 0) {
			firstLine = firstLine.substring(0, newline).strip();
		}
		return firstLine.length() <= TITLE_MAX ? firstLine : firstLine.substring(0, TITLE_MAX - 1) + "…";
	}

	private static long toEpochMillis(LocalDateTime at) {
		return at == null ? 0 : at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	/** Sidebar row: identity + display title + the ordering stamp. */
	public record SessionSummary(String id, String title, long lastActivityAt) {
	}

	/** One replayed turn: the task, its outcome and the pipeline as the dashboard renders it. */
	public record ConversationSession(String id, String title, List<ReplayedTurn> turns) {

		public record ReplayedTurn(String task, String answer, String status, long at,
				List<PipelineEvent> pipeline) {
		}
	}
}

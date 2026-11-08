package com.panic.controller;

import com.panic.core.agent.chat.ChatAccessGuard;
import com.panic.core.agent.memory.Conversation;
import com.panic.core.agent.memory.ConversationStore;
import com.panic.exception.BookingException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session management over the conversation store — the sidebar's API:
 * create, list (latest activity first), whole-session replay, inline rename and delete.
 * Unknown ids answer 404; renaming/deleting a conversation that is currently running a
 * task answers 409; creating or chatting beyond the per-minute quotas answers 429 —
 * all through the shared exception handler.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

	private final ConversationStore sessions;
	private final ChatAccessGuard access;

	public ConversationController(ConversationStore sessions, ChatAccessGuard access) {
		this.sessions = sessions;
		this.access = access;
	}

	/** New empty session; the title arrives with the first user message. */
	@PostMapping
	public ResponseEntity<ConversationCreated> create() {
		access.admitSessionCreation(Conversation.LOCAL_USER);
		String id = sessions.create(Conversation.LOCAL_USER);
		return ResponseEntity.status(201).body(new ConversationCreated(id));
	}

	/** Sidebar rows ordered by latest activity. */
	@GetMapping
	public List<ConversationStore.SessionSummary> list() {
		return sessions.list(Conversation.LOCAL_USER);
	}

	/**
	 * Replay of one session in the AG-UI message shape the runtime restores directly
	 * (`fromAgUiMessages`): one user record + one assistant record per paired turn, ids stable
	 * per replay. `turns` travels beside the messages because the ExecutionLedger renders the
	 * recorded pipeline of each turn from it (the messages array cannot carry pipeline data).
	 */
	@GetMapping("/{id}")
	public ConversationReplay session(@PathVariable String id) {
		ConversationStore.ConversationSession session = sessions.session(id);
		if (session == null) {
			throw new BookingException("Conversation '" + id + "' does not exist",
					HttpURLConnection.HTTP_NOT_FOUND);
		}
		return ConversationReplay.of(session);
	}

	@PatchMapping("/{id}")
	public ConversationStore.SessionSummary rename(@PathVariable String id, @RequestBody TitleChange change) {
		String title = change.title() == null ? "" : change.title().strip();
		if (title.isBlank()) {
			throw new BookingException("Title must not be blank");
		}
		requireConversation(id);
		access.assertConversationIdle(id);
		ConversationStore.SessionSummary summary = sessions.rename(id, title);
		if (summary == null) {
			throw new BookingException("Conversation '" + id + "' does not exist",
					HttpURLConnection.HTTP_NOT_FOUND);
		}
		return summary;
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id) {
		requireConversation(id);
		access.assertConversationIdle(id);
		if (!sessions.delete(id)) {
			throw new BookingException("Conversation '" + id + "' does not exist",
					HttpURLConnection.HTTP_NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}

	private void requireConversation(String id) {
		if (!sessions.exists(id)) {
			throw new BookingException("Conversation '" + id + "' does not exist",
					HttpURLConnection.HTTP_NOT_FOUND);
		}
	}

	public record ConversationCreated(String id) {
	}

	public record TitleChange(String title) {
	}

	/** Session replay: AG-UI conversation records for the Thread, recorded turns for the ledger. */
	public record ConversationReplay(String id, String title, List<AgUiReplayMessage> messages,
			List<ConversationStore.ConversationSession.ReplayedTurn> turns) {

		public static ConversationReplay of(ConversationStore.ConversationSession session) {
			List<AgUiReplayMessage> messages = new ArrayList<>();
			for (int index = 0; index < session.turns().size(); index++) {
				ConversationStore.ConversationSession.ReplayedTurn turn = session.turns().get(index);
				messages.add(new AgUiReplayMessage("t" + index + "-u", "user", turn.task()));
				messages.add(new AgUiReplayMessage("t" + index + "-a", "assistant", turn.answer()));
			}
			return new ConversationReplay(session.id(), session.title(), List.copyOf(messages), session.turns());
		}
	}

	/** One replayed conversation record: user/assistant pair, AG-UI inbound/outbound shape. */
	public record AgUiReplayMessage(String id, String role, String content) {
	}
}

package com.panic.controller;

import com.panic.core.agent.chat.ChatAccessGuard;
import com.panic.core.agent.memory.Conversation;
import com.panic.core.agent.memory.ConversationStore;
import com.panic.exception.BookingException;
import java.net.HttpURLConnection;
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

	/** Replay of one session: task, answer, status and recorded pipeline of every turn. */
	@GetMapping("/{id}")
	public ConversationStore.ConversationSession session(@PathVariable String id) {
		ConversationStore.ConversationSession session = sessions.session(id);
		if (session == null) {
			throw new BookingException("Conversation '" + id + "' does not exist",
					HttpURLConnection.HTTP_NOT_FOUND);
		}
		return session;
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
}

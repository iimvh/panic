package com.panic.core.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the message history of a conversation and loads the last {@code window-turns} turns to
 * feed the root agent. Only the root agent has memory; sub-agents receive the parent's task summary.
 */
@Service
public class ConversationStore {

	private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

	private final ConversationRepository conversations;
	private final ChatTurnRepository turns;
	private final int windowTurns;

	public ConversationStore(ConversationRepository conversations, ChatTurnRepository turns,
			@Value("${app.memory.window-turns:20}") int windowTurns) {
		this.conversations = conversations;
		this.turns = turns;
		this.windowTurns = windowTurns <= 0 ? 20 : windowTurns;
	}

	@Transactional
	public String create() {
		Conversation conversation = new Conversation();
		conversation.setId(UUID.randomUUID().toString());
		conversations.save(conversation);
		return conversation.getId();
	}

	public boolean exists(String conversationId) {
		return conversationId != null && !conversationId.isBlank()
				&& conversations.existsById(conversationId);
	}

	/** Older to newer, capped to the configured window (most recent turns win). */
	@Transactional(readOnly = true)
	public List<Message> loadMessages(String conversationId) {
		if (!exists(conversationId)) {
			return List.of();
		}
		List<ChatTurn> recent = turns.findByConversation_IdOrderByIdDesc(conversationId,
				PageRequest.of(0, windowTurns));
		List<ChatTurn> ordered = new ArrayList<>(recent);
		Collections.reverse(ordered);
		List<Message> messages = new ArrayList<>(ordered.size());
		for (ChatTurn turn : ordered) {
			if (turn.getRole() == ChatTurn.Role.USER) {
				messages.add(new UserMessage(turn.getContent()));
			} else {
				messages.add(new AssistantMessage(turn.getContent()));
			}
		}
		return messages;
	}

	@Transactional
	public void append(String conversationId, ChatTurn.Role role, String content) {
		if (conversationId == null || conversationId.isBlank() || content == null) {
			return;
		}
		Conversation conversation = conversations.findById(conversationId).orElse(null);
		if (conversation == null) {
			log.warn("Skipping memory append: conversation '{}' does not exist", conversationId);
			return;
		}
		ChatTurn turn = new ChatTurn();
		turn.setConversation(conversation);
		turn.setRole(role);
		turn.setContent(content);
		turns.save(turn);
	}
}

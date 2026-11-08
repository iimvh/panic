package com.panic.pipeline.agui;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Run input the application sends to open an exchange (AG-UI spec 1.0 `RunAgentInput`):
 * `threadId` names the conversation, `messages` is the conversation history with the last
 * user message as the task this run answers.
 */
public record RunAgentInput(String threadId, List<InboundMessage> messages) {

	/** Text of the newest user message — the task this run answers. */
	public String lastUserText() {
		if (messages == null) {
			return "";
		}
		for (int i = messages.size() - 1; i >= 0; i--) {
			InboundMessage message = messages.get(i);
			if (message != null && "user".equals(message.role())) {
				return message.content();
			}
		}
		return "";
	}

	/** One conversation record as the client carries it. Text-only `content` for the wave. */
	public record InboundMessage(String id, String role, String content) {
	}
}

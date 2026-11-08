package com.panic.controller;

import com.panic.core.agent.chat.ChatAccessGuard;
import com.panic.core.agent.memory.Conversation;
import com.panic.core.agent.memory.ConversationStore;
import com.panic.core.agent.runtime.AgentExecutor;
import com.panic.exception.BookingException;
import com.panic.pipeline.PipelineBroadcaster;
import com.panic.pipeline.PipelineEvent;
import com.panic.pipeline.PipelineJson;
import com.panic.pipeline.PipelineRecorder;
import com.panic.pipeline.agui.RunAgentInput;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

	private static final Logger log = LoggerFactory.getLogger(AgentController.class);
	private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;
	private static final String ERROR_CODE_REJECTED = "guard_rejected";
	private static final String ERROR_CODE_INTERNAL = "agent_failed";

	private final AgentExecutor executor;
	private final PipelineRecorder pipeline;
	private final PipelineBroadcaster broadcaster;
	private final ConversationStore memory;
	private final ChatAccessGuard access;

	public AgentController(AgentExecutor executor, PipelineRecorder pipeline, PipelineBroadcaster broadcaster,
			ConversationStore memory, ChatAccessGuard access) {
		this.executor = executor;
		this.pipeline = pipeline;
		this.broadcaster = broadcaster;
		this.memory = memory;
		this.access = access;
	}

	/**
	 * Chat endpoint routed through the Orchestrator/Coordinator (idea.md), speaking the AG-UI
	 * protocol (docs.ag-ui.com). The body is a `RunAgentInput`: `threadId` names the conversation
	 * and the last user message of `messages` is the task. The stream opens with `RUN_STARTED`
	 * (echoing `threadId` + `runId` = traceId), streams the answer as a `TEXT_MESSAGE_*` trio,
	 * every pipeline step realtime as a `CUSTOM` `panic.pipeline` data part, and closes with
	 * `RUN_FINISHED` (metadata: traceId, durationMs) — or `RUN_ERROR` when the run fails.
	 * <p>
	 * Admission (per-conversation quota → 429, run lock → 409, session-creation quota → 429) happens on
	 * the request thread <em>before</em> the stream opens, so guard rejections surface as ordinary
	 * HTTP error codes instead of in-stream events. Absent/unknown conversation ids create a fresh
	 * session — `RUN_STARTED` tells the client which one.
	 */
	@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter chat(@RequestBody RunAgentInput input) {
		String userText = input.lastUserText();
		String conversationId = resolveAdmitted(input.threadId());
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
		PipelineRecorder.RequestTrace trace = new PipelineRecorder.RequestTrace();
		emitter.onTimeout(emitter::complete);
		emitter.onError(error -> emitter.complete());
		AgUiChatStream stream = new AgUiChatStream(emitter, conversationId, trace.id());

		// Publish each recorded event straight to this stream (realtime, node by node).
		broadcaster.register(trace.id(), stream);

		try {
			stream.runStarted();
			pipeline.startResponse(trace);
			// The user's message as the first conversational entry of the trajectory stream.
			pipeline.emitDone(trace, PipelineEvent.PHASE_MESSAGE, PipelineEvent.STATUS_OK, "user",
					preview(userText, 80),
					PipelineJson.of(Map.of("text", userText)), 0, null, PipelineEvent.ROLE_USER);
		} catch (RuntimeException ex) {
			access.releaseChat(conversationId);
			finishWithError(emitter, stream, trace, ex, conversationId);
			return emitter;
		}

		Thread worker = new Thread(() -> runAndFinish(emitter, stream, trace, userText, conversationId),
				"agent-chat-" + trace.id());
		worker.setDaemon(true);
		worker.start();
		return emitter;
	}

	/**
	 * Reuses an existing conversation id (admitting the send against its quota and run lock), or
	 * creates a new one when absent/unknown — the creation itself counts against the per-user
	 * session quota, and the fresh conversation is locked like any other before its turn runs.
	 */
	private String resolveAdmitted(String conversationId) {
		if (memory.exists(conversationId)) {
			access.admitChat(conversationId);
			return conversationId;
		}
		access.admitSessionCreation(Conversation.LOCAL_USER);
		String created = memory.create(Conversation.LOCAL_USER);
		access.acquireLock(created);
		return created;
	}

	private void runAndFinish(SseEmitter emitter, AgUiChatStream stream, PipelineRecorder.RequestTrace trace,
			String userText, String conversationId) {
		// The trace was opened on the request thread; bind it here so the agent loop records into it.
		pipeline.bind(trace);
		try {
			executor.runRoot(conversationId, userText).text();
			List<PipelineEvent> events = List.of(pipeline.end(trace, false, null));
			persistTrace(conversationId, trace.id(), events);
			stream.runFinished(trace.durationMs());
			emitter.complete();
		} catch (RuntimeException ex) {
			finishWithError(emitter, stream, trace, ex, conversationId);
		} finally {
			pipeline.unbind();
			broadcaster.unregister(trace.id());
			access.releaseChat(conversationId);
		}
	}

	private void finishWithError(SseEmitter emitter, AgUiChatStream stream, PipelineRecorder.RequestTrace trace,
			RuntimeException ex, String conversationId) {
		String error = ex instanceof BookingException
				? ex.getMessage()
				: ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
		String code = ex instanceof BookingException ? ERROR_CODE_REJECTED : ERROR_CODE_INTERNAL;
		try {
			pipeline.emitDone(trace, PipelineEvent.PHASE_ERROR, PipelineEvent.STATUS_ERROR, "coordinator",
					"Pipeline error", error, trace.durationMs());
			List<PipelineEvent> events = List.of(pipeline.end(trace, true, error));
			persistTrace(conversationId, trace.id(), events);
			stream.runFailed(error, code);
			emitter.complete();
		} catch (RuntimeException ignored) {
			emitter.completeWithError(ex);
		} finally {
			broadcaster.unregister(trace.id());
		}
	}

	/** The finished trace is part of the session record — but never at the cost of the reply itself. */
	private void persistTrace(String conversationId, long traceId, List<PipelineEvent> events) {
		if (conversationId == null || conversationId.isBlank()) {
			return;
		}
		try {
			memory.recordTrace(conversationId, traceId, events);
		} catch (RuntimeException ex) {
			log.warn("Could not persist trace {} of conversation '{}': {}", traceId, conversationId,
					ex.getMessage());
		}
	}

	/** First line/80 chars of a message for the trajectory preview. */
	private static String preview(String text, int max) {
		if (text == null) {
			return "";
		}
		int end = Math.min(text.length(), max);
		int newline = text.indexOf('\n');
		if (newline > 0) {
			end = Math.min(end, newline);
		}
		String cut = text.substring(0, end);
		return (cut.length() < text.length() ? cut + "…" : cut).trim();
	}
}

package com.panic.controller;

import com.panic.core.agent.memory.ConversationStore;
import com.panic.core.agent.runtime.AgentExecutor;
import com.panic.exception.BookingException;
import com.panic.pipeline.PipelineBroadcaster;
import com.panic.pipeline.PipelineEvent;
import com.panic.pipeline.PipelineJson;
import com.panic.pipeline.PipelineRecorder;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

	/** Name of the frame holding the final /chat result. */
	public static final String RESULT_EVENT = "result";
	/** Name of the frame carrying each realtime pipeline step. */
	public static final String PIPELINE_EVENT = PipelineBroadcaster.EVENT_PIPELINE;
	/** Name of the opening frame (traceId + conversationId). */
	public static final String START_EVENT = "start";

	private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;

	private final AgentExecutor executor;
	private final PipelineRecorder pipeline;
	private final PipelineBroadcaster broadcaster;
	private final ConversationStore memory;

	public AgentController(AgentExecutor executor, PipelineRecorder pipeline, PipelineBroadcaster broadcaster,
			ConversationStore memory) {
		this.executor = executor;
		this.pipeline = pipeline;
		this.broadcaster = broadcaster;
		this.memory = memory;
	}

	/**
	 * Chat endpoint routed through the Orchestrator/Coordinator (idea.md).
	 * <p>
	 * Returns text/event-stream. The first frame (`start`) carries the traceId + conversationId; every
	 * pipeline step is pushed as a `pipeline` frame <em>as it happens</em>, and the final `result` frame
	 * holds the reply + all steps. Pipeline frames are delivered directly from the recorder, so they do
	 * not wait for the agent to finish.
	 */
	@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter chat(@RequestBody ChatRequest request) {
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
		PipelineRecorder.RequestTrace trace = new PipelineRecorder.RequestTrace();
		emitter.onTimeout(emitter::complete);
		emitter.onError(error -> emitter.complete());

		// Publish each recorded event straight to this stream (realtime, node by node).
		broadcaster.register(trace.id(), emitter);

		String conversationId;
		try {
			conversationId = resolveConversation(request.conversationId());
			// Send the header first so the client learns the traceId before any pipeline frame.
			send(emitter, START_EVENT, new StartFrame(trace.id(), conversationId));
			pipeline.startResponse(trace);
			// The user's message as the first conversational entry of the trajectory stream.
			pipeline.emitDone(trace, PipelineEvent.PHASE_MESSAGE, PipelineEvent.STATUS_OK, "user",
					preview(request.message(), 80),
					PipelineJson.of(Map.of("text", request.message())), 0, null, PipelineEvent.ROLE_USER);
		} catch (RuntimeException ex) {
			finishWithError(emitter, trace, ex);
			return emitter;
		}

		Thread worker = new Thread(() -> runAndFinish(emitter, trace, request, conversationId),
				"agent-chat-" + trace.id());
		worker.setDaemon(true);
		worker.start();
		return emitter;
	}

	/** Reuses an existing conversation id, or creates a new one when absent/unknown. */
	private String resolveConversation(String conversationId) {
		return memory.exists(conversationId) ? conversationId : memory.create();
	}

	private void runAndFinish(SseEmitter emitter, PipelineRecorder.RequestTrace trace, ChatRequest request,
			String conversationId) {
		// The trace was opened on the request thread; bind it here so the agent loop records into it.
		pipeline.bind(trace);
		try {
			String reply = executor.runRoot(conversationId, request.message()).text();
			List<PipelineEvent> events = List.of(pipeline.end(trace, false, null));
			send(emitter, RESULT_EVENT, new AgentReply(reply, trace.id(), events, null, conversationId));
			emitter.complete();
		} catch (RuntimeException ex) {
			finishWithError(emitter, trace, ex, conversationId);
		} finally {
			pipeline.unbind();
			broadcaster.unregister(trace.id());
		}
	}

	private void finishWithError(SseEmitter emitter, PipelineRecorder.RequestTrace trace, RuntimeException ex) {
		finishWithError(emitter, trace, ex, null);
	}

	private void finishWithError(SseEmitter emitter, PipelineRecorder.RequestTrace trace, RuntimeException ex,
			String conversationId) {
		String error = ex instanceof BookingException
				? ex.getMessage()
				: ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
		try {
			pipeline.emitDone(trace, PipelineEvent.PHASE_ERROR, PipelineEvent.STATUS_ERROR, "coordinator",
					"Pipeline error", error, trace.durationMs());
			List<PipelineEvent> events = List.of(pipeline.end(trace, true, error));
			send(emitter, RESULT_EVENT, new AgentReply(null, trace.id(), events, error, conversationId));
			emitter.complete();
		} catch (RuntimeException ignored) {
			emitter.completeWithError(ex);
		} finally {
			broadcaster.unregister(trace.id());
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

	private static void send(SseEmitter emitter, String name, Object data) {
		try {
			emitter.send(SseEmitter.event().name(name).data(PipelineJson.json(data), MediaType.TEXT_PLAIN));
		} catch (IOException | IllegalStateException ex) {
			emitter.completeWithError(ex);
		}
	}

	public record ChatRequest(String message, String conversationId) {
	}

	public record StartFrame(long traceId, String conversationId) {
	}

	/**
	 * Final result of one chat turn. A non-null `error` means the agent failed;
	 * `pipeline` always holds the executed steps so the UI can draw them even when SSE is unavailable.
	 */
	public record AgentReply(String reply, long traceId, List<PipelineEvent> pipeline, String error,
			String conversationId) {
	}
}

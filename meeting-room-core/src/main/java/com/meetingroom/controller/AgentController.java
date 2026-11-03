package com.meetingroom.controller;

import com.meetingroom.core.agent.runtime.AgentExecutor;
import com.meetingroom.exception.BookingException;
import com.meetingroom.pipeline.PipelineBroadcaster;
import com.meetingroom.pipeline.PipelineEvent;
import com.meetingroom.pipeline.PipelineJson;
import com.meetingroom.pipeline.PipelineRecorder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

	/** Name of the frame holding the final /chat result. */
	public static final String RESULT_EVENT = "result";
	/** Name of the frame carrying each realtime pipeline step. */
	public static final String PIPELINE_EVENT = PipelineBroadcaster.EVENT_PIPELINE;

	private final AgentExecutor executor;
	private final PipelineRecorder pipeline;
	private final PipelineBroadcaster broadcaster;

	public AgentController(AgentExecutor executor, PipelineRecorder pipeline, PipelineBroadcaster broadcaster) {
		this.executor = executor;
		this.pipeline = pipeline;
		this.broadcaster = broadcaster;
	}

	/**
	 * Chat endpoint routed through the Orchestrator/Coordinator (idea.md).
	 * <p>
	 * Returns text/event-stream: the header + first frame (`start`) are flushed immediately so the
	 * client learns the traceId and opens {@code /pipeline/stream}; the final `result` frame carries
	 * the reply + all steps. This does not depend on whether Tomcat streams the body incrementally.
	 */
	@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public StreamingResponseBody chat(@RequestBody ChatRequest request) {
		return output -> {
			PipelineRecorder.RequestTrace trace = new PipelineRecorder.RequestTrace();
			pipeline.startResponse(trace);
			writeFrame(output, "start", new StartFrame(trace.id()));
			try {
				String reply = executor.runRoot(request.message()).text();
				List<PipelineEvent> events = List.of(pipeline.end(trace, false, null));
				writeFrame(output, RESULT_EVENT, new AgentReply(reply, trace.id(), events, null));
			} catch (RuntimeException ex) {
				String error = ex instanceof BookingException
						? ex.getMessage()
						: ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
				pipeline.emitDone(trace, PipelineEvent.PHASE_ERROR, PipelineEvent.STATUS_ERROR, "coordinator",
						"Pipeline error", error, trace.durationMs());
				List<PipelineEvent> events = List.of(pipeline.end(trace, true, error));
				writeFrame(output, RESULT_EVENT, new AgentReply(null, trace.id(), events, error));
			}
		};
	}

	/** Streams a request's realtime pipeline steps (the client opens it right after the `start` frame). */
	@GetMapping(value = "/pipeline/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@RequestParam long traceId) {
		return broadcaster.subscribe(traceId);
	}

	private static void writeFrame(OutputStream output, String name, Object data) throws IOException {
		output.write(("event:" + name + "\n").getBytes(StandardCharsets.UTF_8));
		output.write(("data:" + PipelineJson.json(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
		output.flush();
	}

	public record ChatRequest(String message) {
	}

	public record StartFrame(long traceId) {
	}

	/**
	 * Final result of one chat turn. A non-null `error` means the agent failed;
	 * `pipeline` always holds the executed steps so the UI can draw them even when SSE is unavailable.
	 */
	public record AgentReply(String reply, long traceId, List<PipelineEvent> pipeline, String error) {
	}
}

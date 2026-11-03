package com.meetingroom.agents.cancellation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingroom.domain.model.Meeting;
import com.meetingroom.domain.service.MeetingService;
import com.meetingroom.exception.BookingException;
import com.meetingroom.pipeline.LlmCallAdvisor;
import com.meetingroom.pipeline.PipelineEvent;
import com.meetingroom.pipeline.PipelineJson;
import com.meetingroom.pipeline.PipelineRecorder;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Local tools of the {@code cancellation} agent: parse meetingId, then call {@link MeetingService#cancel}. */
@Component
public class CancellationTools {

	private static final String AGENT = "cancellation";

	private static final String PARSE_SYSTEM = """
			You are a meeting cancellation agent. Extract the user request into ONE JSON object:
			{ "meetingId": integer or null }.
			Never invent a meetingId; if the user does not state the id of the meeting to cancel, return null.
			Return plain JSON only, no markdown, no explanation.
			""";

	private final ChatClient chatClient;
	private final MeetingService meetingService;
	private final PipelineRecorder recorder;
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private final boolean aiEnabled;

	public CancellationTools(ChatClient.Builder chatClientBuilder, MeetingService meetingService,
			PipelineRecorder recorder, @Value("${spring.ai.openai.api-key:}") String apiKey) {
		this.meetingService = meetingService;
		this.recorder = recorder;
		this.aiEnabled = com.meetingroom.core.agent.runtime.AgentApiKey.isConfigured(apiKey);
		this.chatClient = this.aiEnabled ? chatClientBuilder.build() : null;
	}

	@Tool(name = "cancelMeeting",
			description = "Cancel a meeting by its id. Use when the user wants to cancel/delete an existing meeting.")
	public String cancelMeeting(@ToolParam(description = "Cancellation request in natural language") String query) {
		Long meetingId = parseMeetingId(query);
		PipelineRecorder.RequestTrace trace = recorder.currentTrace();
		long start = System.currentTimeMillis();
		Meeting meeting = meetingService.get(meetingId);
		meetingService.cancel(meetingId);
		recorder.emitDone(trace, PipelineEvent.PHASE_TOOL, PipelineEvent.STATUS_OK, AGENT,
				"MeetingService.cancel", PipelineJson.of(Map.of("meetingId", meetingId)),
				System.currentTimeMillis() - start, AGENT);
		return "Cancelled meeting id=" + meetingId + " (title='" + meeting.getTitle() + "' at "
				+ meeting.getStartTime() + " - " + meeting.getEndTime() + ")";
	}

	Long parseMeetingId(String query) {
		if (!aiEnabled) {
			throw new BookingException("Agent AI is not enabled: please set OPENROUTER_API_KEY");
		}
		PipelineRecorder.RequestTrace trace = recorder.currentTrace();
		PipelineRecorder.ActiveAgent active = recorder.activeAgent();
		long start = System.currentTimeMillis();
		String json = chatClient.prompt()
				.system(PARSE_SYSTEM)
				.user(query)
				.advisors(LlmCallAdvisor.forAgent(AGENT, recorder, trace,
						active == null ? null : active.parentEventId()))
				.call()
				.content();
		if (!StringUtils.hasText(json)) {
			throw new BookingException("Could not extract the meeting id from the request");
		}
		String cleaned = json.replaceAll("```json\\n?", "").replaceAll("```\\n?", "").trim();
		try {
			MeetingId target = objectMapper.readValue(cleaned, MeetingId.class);
			if (target.meetingId() == null) {
				recorder.emitDone(trace, PipelineEvent.PHASE_PARSE, PipelineEvent.STATUS_ERROR, AGENT,
						"Missing meetingId", "Guardrail: LLM returned meetingId=null",
						System.currentTimeMillis() - start, AGENT);
				throw new BookingException("Please provide the id of the meeting to cancel");
			}
			recorder.emitDone(trace, PipelineEvent.PHASE_PARSE, PipelineEvent.STATUS_OK, AGENT,
					"Parse meetingId", PipelineJson.of(Map.of("meetingId", target.meetingId())),
					System.currentTimeMillis() - start, AGENT);
			return target.meetingId();
		} catch (BookingException ex) {
			throw ex;
		} catch (Exception ex) {
			recorder.emitDone(trace, PipelineEvent.PHASE_PARSE, PipelineEvent.STATUS_ERROR, AGENT,
					"JSON parsing failed", PipelineJson.text(ex.getMessage()), System.currentTimeMillis() - start,
					AGENT);
			throw new BookingException("Could not parse the meeting id: " + ex.getMessage());
		}
	}

	private record MeetingId(Long meetingId) {
	}
}

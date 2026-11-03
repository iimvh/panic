package com.meetingroom.agents.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingroom.domain.model.BookingRequest;
import com.meetingroom.domain.model.Meeting;
import com.meetingroom.domain.service.MeetingService;
import com.meetingroom.exception.BookingException;
import com.meetingroom.pipeline.LlmCallAdvisor;
import com.meetingroom.pipeline.PipelineEvent;
import com.meetingroom.pipeline.PipelineJson;
import com.meetingroom.pipeline.PipelineRecorder;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Local tools of the {@code booking} agent: parse the request, then call {@link MeetingService#book}. */
@Component
public class BookingTools {

	private static final String AGENT = "booking";

	private static final String PARSE_SYSTEM = """
			You are a meeting-room booking agent. Extract the user request into ONE JSON object:
			{ "roomId": integer or null, "title": "string or null", "organizer": "string or null",
			"attendees": ["list of emails", "or null"], "startTime": "ISO-8601 or null", "endTime": "ISO-8601 or null" }.
			The current time is {now}. Convert relative expressions ("tomorrow", "next week", "2pm") to ISO-8601; if only a time is given, use today's date.
			APART from data the user explicitly provides, never invent roomId, organizer or the attendee list.
			Return plain JSON only, no markdown, no explanation.
			""";

	private final ChatClient chatClient;
	private final MeetingService meetingService;
	private final PipelineRecorder recorder;
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private final boolean aiEnabled;

	public BookingTools(ChatClient.Builder chatClientBuilder, MeetingService meetingService,
			PipelineRecorder recorder, @Value("${spring.ai.openai.api-key:}") String apiKey) {
		this.meetingService = meetingService;
		this.recorder = recorder;
		this.aiEnabled = com.meetingroom.core.agent.runtime.AgentApiKey.isConfigured(apiKey);
		this.chatClient = this.aiEnabled ? chatClientBuilder.build() : null;
	}

	@Tool(name = "bookMeeting",
			description = "Book a meeting with full details (roomId, title, organizer, attendees, startTime, endTime). Use when the user wants to book/schedule a new meeting.")
	public String bookMeeting(@ToolParam(description = "Booking request in natural language") String query) {
		BookingRequest request = parseRequest(query);
		PipelineRecorder.RequestTrace trace = recorder.currentTrace();
		long start = System.currentTimeMillis();
		try {
			Meeting meeting = meetingService.book(request);
			recorder.emitDone(trace, PipelineEvent.PHASE_TOOL, PipelineEvent.STATUS_OK, AGENT,
					"MeetingService.book",
					PipelineJson.of(Map.of("roomId", String.valueOf(request.roomId()),
							"meetingId", meeting.getId(),
							"start", String.valueOf(meeting.getStartTime()),
							"end", String.valueOf(meeting.getEndTime()))),
					System.currentTimeMillis() - start, AGENT);
			return "Booking confirmed: id=" + meeting.getId()
					+ " room=" + meeting.getRoom().getName()
					+ " title='" + meeting.getTitle() + "'"
					+ " from " + meeting.getStartTime() + " to " + meeting.getEndTime()
					+ " | organizer=" + meeting.getOrganizer()
					+ " | status=" + meeting.getStatus();
		} catch (RuntimeException ex) {
			recorder.emitDone(trace, PipelineEvent.PHASE_TOOL, PipelineEvent.STATUS_ERROR, AGENT,
					"MeetingService.book rejected", PipelineJson.text(String.valueOf(ex.getMessage())),
					System.currentTimeMillis() - start, AGENT);
			throw ex;
		}
	}

	BookingRequest parseRequest(String query) {
		if (!aiEnabled) {
			throw new BookingException("Agent AI is not enabled: please set OPENROUTER_API_KEY");
		}
		PipelineRecorder.RequestTrace trace = recorder.currentTrace();
		PipelineRecorder.ActiveAgent active = recorder.activeAgent();
		long start = System.currentTimeMillis();
		String json = chatClient.prompt()
				.system(PARSE_SYSTEM.replace("{now}", LocalDateTime.now().toString()))
				.user(query)
				.advisors(LlmCallAdvisor.forAgent(AGENT, recorder, trace,
						active == null ? null : active.parentEventId()))
				.call()
				.content();
		if (!StringUtils.hasText(json)) {
			throw new BookingException("Could not extract booking details from the request");
		}
		String cleaned = json.replaceAll("```json\\n?", "").replaceAll("```\\n?", "").trim();
		try {
			BookingRequest request = objectMapper.readValue(cleaned, BookingRequest.class);
			recorder.emitDone(trace, PipelineEvent.PHASE_PARSE, PipelineEvent.STATUS_OK, AGENT,
					"Parsing booking request",
					PipelineJson.of(Map.of("roomId", String.valueOf(request.roomId()))),
					System.currentTimeMillis() - start, AGENT);
			return request;
		} catch (Exception ex) {
			recorder.emitDone(trace, PipelineEvent.PHASE_PARSE, PipelineEvent.STATUS_ERROR, AGENT,
					"JSON parsing failed", PipelineJson.text(ex.getMessage()), System.currentTimeMillis() - start,
					AGENT);
			throw new BookingException("Could not parse the booking details: " + ex.getMessage());
		}
	}
}

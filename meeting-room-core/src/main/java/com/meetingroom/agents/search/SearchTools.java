package com.meetingroom.agents.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingroom.domain.model.MeetingRoom;
import com.meetingroom.domain.model.RoomSearchCriteria;
import com.meetingroom.domain.service.MeetingRoomService;
import com.meetingroom.exception.BookingException;
import com.meetingroom.pipeline.PipelineEvent;
import com.meetingroom.pipeline.PipelineJson;
import com.meetingroom.pipeline.PipelineRecorder;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Local tools of the {@code search} agent: parse a natural-language request into
 * {@link RoomSearchCriteria}, then query {@link MeetingRoomService}.
 */
@Component
public class SearchTools {

	private static final String AGENT = "search";

	private static final String PARSE_SYSTEM = """
			You are a meeting-room search agent. Extract the user request into ONE JSON object:
			{ "startTime": "ISO-8601 or null", "endTime": "ISO-8601 or null", "capacity": integer or null, "location": "string or null" }.
			The current time is {now}. Convert relative expressions ("tomorrow", "next week", "2pm") to ISO-8601; if only a time is given, use today's date.
			Return plain JSON only, no markdown, no explanation.
			""";

	private final ChatClient chatClient;
	private final MeetingRoomService roomService;
	private final PipelineRecorder recorder;
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private final boolean aiEnabled;

	public SearchTools(ChatClient.Builder chatClientBuilder, MeetingRoomService roomService,
			PipelineRecorder recorder, @Value("${spring.ai.openai.api-key:}") String apiKey) {
		this.roomService = roomService;
		this.recorder = recorder;
		this.aiEnabled = com.meetingroom.core.agent.runtime.AgentApiKey.isConfigured(apiKey);
		this.chatClient = this.aiEnabled ? chatClientBuilder.build() : null;
	}

	@Tool(name = "searchRooms",
			description = "Find available meeting rooms from a natural-language request (time, headcount, location). Use when the user wants to look up free rooms.")
	public String searchRooms(@ToolParam(description = "Room search request in natural language") String query) {
		RoomSearchCriteria criteria = parseCriteria(query);
		PipelineRecorder.RequestTrace trace = recorder.currentTrace();
		long start = System.currentTimeMillis();
		List<MeetingRoom> rooms = roomService.searchAvailable(criteria);
		recorder.emitDone(trace, PipelineEvent.PHASE_TOOL, PipelineEvent.STATUS_OK, AGENT,
				"MeetingRoomService.searchAvailable",
				PipelineJson.of(Map.of("criteria", String.valueOf(criteria), "rooms", rooms.size())),
				System.currentTimeMillis() - start, AGENT);
		return formatRooms(criteria, rooms);
	}

	RoomSearchCriteria parseCriteria(String query) {
		if (!aiEnabled) {
			throw new BookingException("Agent AI is not enabled: please set OPENROUTER_API_KEY");
		}
		PipelineRecorder.RequestTrace trace = recorder.currentTrace();
		PipelineRecorder.ActiveAgent active = recorder.activeAgent();
		String system = PARSE_SYSTEM.replace("{now}", java.time.LocalDateTime.now().toString());
		long start = System.currentTimeMillis();
		String json = chatClient.prompt()
				.system(system)
				.user(query)
				.advisors(com.meetingroom.pipeline.LlmCallAdvisor.forAgent(AGENT, recorder, trace,
						active == null ? null : active.parentEventId()))
				.call()
				.content();
		if (!StringUtils.hasText(json)) {
			throw new BookingException("Could not extract search criteria from the request");
		}
		String cleaned = json.replaceAll("```json\\n?", "").replaceAll("```\\n?", "").trim();
		try {
			RoomSearchCriteria criteria = objectMapper.readValue(cleaned, RoomSearchCriteria.class);
			recorder.emitDone(trace, PipelineEvent.PHASE_PARSE, PipelineEvent.STATUS_OK, AGENT,
					"Parsing search criteria", PipelineJson.of(Map.of("criteria", String.valueOf(criteria))),
					System.currentTimeMillis() - start, AGENT);
			return criteria;
		} catch (Exception ex) {
			recorder.emitDone(trace, PipelineEvent.PHASE_PARSE, PipelineEvent.STATUS_ERROR, AGENT,
					"JSON parsing failed", PipelineJson.text(ex.getMessage()), System.currentTimeMillis() - start,
					AGENT);
			throw new BookingException("Could not parse the search criteria: " + ex.getMessage());
		}
	}

	private String formatRooms(RoomSearchCriteria criteria, List<MeetingRoom> rooms) {
		if (rooms.isEmpty()) {
			return "No rooms match the criteria: " + criteria;
		}
		return "Found " + rooms.size() + " available room(s):\n" + rooms.stream()
				.map(r -> "- id=" + r.getId() + " | name=" + r.getName()
						+ " | capacity=" + r.getCapacity() + " | location=" + r.getLocation())
				.collect(Collectors.joining("\n"));
	}
}

package com.meetingroom.agents.booking;

import com.meetingroom.core.agent.tool.LocalToolSource;
import com.meetingroom.core.agent.spi.ToolSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "booking" agent plugin: only registers the local {@code bookMeeting} tool.
 * <p>
 * Metadata (description, prompt, skills, sub-agents) lives in
 * {@code resources/agents/booking/agent.yaml} + {@code system.md}; the YAML declaration refers to
 * the tool source via {@code local:booking-tools}.
 */
@Configuration
@ConditionalOnProperty(name = "app.agents.booking.enabled", havingValue = "true", matchIfMissing = true)
public class BookingAgentConfig {

	public static final String TOOL_SOURCE = "booking-tools";

	@Bean
	public ToolSource bookingToolSource(BookingTools tools) {
		return LocalToolSource.of(TOOL_SOURCE, tools);
	}
}

package com.meetingroom.agents.cancellation;

import com.meetingroom.core.agent.tool.LocalToolSource;
import com.meetingroom.core.agent.spi.ToolSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "cancellation" agent plugin: only registers the local {@code cancelMeeting} tool.
 * Metadata lives in {@code resources/agents/cancellation/}.
 */
@Configuration
@ConditionalOnProperty(name = "app.agents.cancellation.enabled", havingValue = "true", matchIfMissing = true)
public class CancellationAgentConfig {

	public static final String TOOL_SOURCE = "cancellation-tools";

	@Bean
	public ToolSource cancellationToolSource(CancellationTools tools) {
		return LocalToolSource.of(TOOL_SOURCE, tools);
	}
}

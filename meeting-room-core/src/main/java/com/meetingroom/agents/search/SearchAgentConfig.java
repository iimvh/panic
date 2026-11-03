package com.meetingroom.agents.search;

import com.meetingroom.core.agent.tool.LocalToolSource;
import com.meetingroom.core.agent.spi.ToolSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "search" agent plugin: only registers the local {@code searchRooms} tool.
 * Metadata and the {@code booking-policy} skill live in {@code resources/agents/search/}.
 */
@Configuration
@ConditionalOnProperty(name = "app.agents.search.enabled", havingValue = "true", matchIfMissing = true)
public class SearchAgentConfig {

	public static final String TOOL_SOURCE = "search-tools";

	@Bean
	public ToolSource searchToolSource(SearchTools tools) {
		return LocalToolSource.of(TOOL_SOURCE, tools);
	}
}

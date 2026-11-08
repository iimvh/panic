package com.panic.tools.web;

import com.panic.core.agent.spi.ToolPlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mounts the tool-plugin hub's first common tool: {@code web-search}. The plugin is picked up by
 * {@code AgentRuntimeConfig}'s {@code ToolPluginHub} and is only mounted for agents that grant it
 * through {@code hubTools: [web-search]}. Disable with {@code app.tools.web-search.enabled=false}.
 * <p>
 * The search backend is pluggable and key-free. Default is {@code bing} (Bing's HTML page), because
 * DuckDuckGo answers HTTP 202 with an anomaly challenge from many server IPs; set
 * {@code app.tools.web-search.backend=duckduckgo} to use DuckDuckGo's HTML endpoint instead.
 */
@Configuration
@ConditionalOnProperty(name = "app.tools.web-search.enabled", havingValue = "true", matchIfMissing = true)
public class WebSearchToolConfig {

	@Bean
	public SearchBackend webSearchBackend(
			@Value("${app.tools.web-search.backend:bing}") String backend) {
		if ("duckduckgo".equalsIgnoreCase(backend)) {
			return new DuckDuckGoHtmlSearchBackend();
		}
		return new BingHtmlSearchBackend();
	}

	@Bean
	public ToolPlugin webSearchPlugin(SearchBackend backend) {
		return new WebSearchPlugin(backend);
	}
}

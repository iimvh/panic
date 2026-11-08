package com.panic.tools.web;

import com.panic.core.agent.spi.ToolPlugin;
import java.time.Clock;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mounts the tool-plugin hub's first common tool: {@code web-search}. The plugin is picked up by
 * {@code AgentRuntimeConfig}'s {@code ToolPluginHub} and is only mounted for agents that grant it
 * through {@code hubTools: [web-search]}. Disable with {@code app.tools.web-search.enabled=false}.
 * <p>
 * Default backend is {@code google-news}, which searches Google News' RSS feed: every hit carries a
 * real publication timestamp and the query is limited to the last day, so a digest reports today's
 * news instead of evergreen landing pages. {@code bing} (HTML results, no dates) and
 * {@code duckduckgo} remain selectable for general web search via
 * {@code app.tools.web-search.backend}. {@code app.tools.web-search.news.locale} (default
 * {@code en-US}) and {@code app.tools.web-search.news.max-age-hours} (default {@code 48}) tune the
 * news backend.
 */
@Configuration
@ConditionalOnProperty(name = "app.tools.web-search.enabled", havingValue = "true", matchIfMissing = true)
public class WebSearchToolConfig {

	@Bean
	public SearchBackend webSearchBackend(
			@Value("${app.tools.web-search.backend:google-news}") String backend,
			@Value("${app.tools.web-search.news.locale:en-US}") String locale,
			@Value("${app.tools.web-search.news.max-age-hours:48}") long maxAgeHours) {
		if ("duckduckgo".equalsIgnoreCase(backend)) {
			return new DuckDuckGoHtmlSearchBackend();
		}
		if ("bing".equalsIgnoreCase(backend)) {
			return new BingHtmlSearchBackend();
		}
		return new GoogleNewsRssSearchBackend(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
						.followRedirects(HttpClient.Redirect.NORMAL).build(),
				locale, Duration.ofHours(maxAgeHours), Clock.systemUTC());
	}

	@Bean
	public ToolPlugin webSearchPlugin(SearchBackend backend) {
		return new WebSearchPlugin(backend);
	}
}

package com.panic.tools.web;

import com.panic.core.agent.spi.ToolPlugin;
import com.panic.core.agent.tool.LocalToolSource;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * The {@code web-search} common tool of the tool-plugin hub. Any agent granted {@code hubTools:
 * [web-search]} can call {@code <agentId>__webSearch}. Backed by DuckDuckGo's HTML endpoint by
 * default (zero-config, no key); swapping to another engine is a change of {@link SearchBackend}.
 */
public final class WebSearchPlugin implements ToolPlugin {

	public static final String ID = "web-search";

	private final List<ToolCallback> callbacks;

	public WebSearchPlugin(SearchBackend backend) {
		this.callbacks = LocalToolSource.of(ID, new WebSearchTools(backend)).toolCallbacks();
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String description() {
		return "Search the public web (title, url, snippet) for current news and recent events.";
	}

	@Override
	public List<ToolCallback> toolCallbacks() {
		return callbacks;
	}
}

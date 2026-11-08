package com.panic.core.agent.runtime;

import com.panic.core.agent.spi.ToolPlugin;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/** Minimal tool plugin for hub/loader tests. */
final class ToolPluginStub implements ToolPlugin {

	private final String id;

	private ToolPluginStub(String id) {
		this.id = id;
	}

	static ToolPluginStub of(String id) {
		return new ToolPluginStub(id);
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String description() {
		return "stub plugin " + id;
	}

	@Override
	public List<ToolCallback> toolCallbacks() {
		return List.of();
	}
}

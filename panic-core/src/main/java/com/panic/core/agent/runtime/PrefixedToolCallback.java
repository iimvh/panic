package com.panic.core.agent.runtime;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a raw tool so the LLM only sees {@code <agentId>__<rawName>}; calls forward the
 * arguments + context unchanged to the original tool.
 */
public final class PrefixedToolCallback implements ToolCallback {

	private final ToolCallback delegate;
	private final ToolDefinition definition;

	public PrefixedToolCallback(ToolCallback delegate, String agentId) {
		if (delegate == null) {
			throw new IllegalArgumentException("delegate tool must not be null");
		}
		this.delegate = delegate;
		ToolDefinition raw = delegate.getToolDefinition();
		this.definition = DefaultToolDefinition.builder()
				.name(ToolNamePrefix.apply(agentId, raw.name()))
				.description(raw.description())
				.inputSchema(raw.inputSchema())
				.build();
	}

	public ToolCallback delegate() {
		return delegate;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return definition;
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return delegate.call(toolInput);
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		return delegate.call(toolInput, toolContext);
	}
}

package com.panic.core.agent.runtime;

import com.panic.core.agent.spi.ProviderKind;
import com.panic.core.agent.spi.ToolSource;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/** Minimal tool source for loader tests (no Spring AI tool infrastructure needed). */
final class LocalToolSourceStub implements ToolSource {

	private final String id;

	private LocalToolSourceStub(String id) {
		this.id = id;
	}

	static LocalToolSourceStub of(String id) {
		return new LocalToolSourceStub(id);
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public ProviderKind provider() {
		return ProviderKind.LOCAL;
	}

	@Override
	public List<ToolCallback> toolCallbacks() {
		return List.of();
	}
}

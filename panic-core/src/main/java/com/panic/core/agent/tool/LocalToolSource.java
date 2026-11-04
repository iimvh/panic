package com.panic.core.agent.tool;

import com.panic.core.agent.spi.ProviderKind;
import com.panic.core.agent.spi.ToolSource;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * App-local tools: an object with {@code @Tool} methods or a hand-built list of {@link ToolCallback}s.
 * Wrapping only; name prefixing is done by {@code AgentExecutor}.
 */
public final class LocalToolSource implements ToolSource {

	private final String id;
	private final List<ToolCallback> callbacks;

	private LocalToolSource(String id, List<ToolCallback> callbacks) {
		this.id = id;
		this.callbacks = List.copyOf(callbacks);
	}

	public static LocalToolSource of(String id, Object toolObject) {
		return new LocalToolSource(id, List.of(MethodToolCallbackProvider.builder()
				.toolObjects(toolObject)
				.build()
				.getToolCallbacks()));
	}

	public static LocalToolSource of(String id, ToolCallback... callbacks) {
		return new LocalToolSource(id, Arrays.asList(callbacks));
	}

	public static LocalToolSource of(String id, List<ToolCallback> callbacks) {
		return new LocalToolSource(id, callbacks);
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
		return callbacks;
	}
}

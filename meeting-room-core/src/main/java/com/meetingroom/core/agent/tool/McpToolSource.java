package com.meetingroom.core.agent.tool;

import com.meetingroom.core.agent.spi.ProviderKind;
import com.meetingroom.core.agent.spi.ToolSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * Tools from one MCP server declared in the Spring AI MCP client
 * ({@code spring.ai.mcp.client.sse.connections.<serverName>}).
 * <p>
 * Spring AI keeps the server's tool names (single connection) or prefixes them with
 * {@code spring_ai_mcp_client_<connection>_} / {@code <connection>_} (multiple connections).
 * Here everything is normalised back to raw names so {@code AgentExecutor} only adds {@code <agentId>__}.
 * <p>
 * When the server cannot be reached (disabled, wrong URL, timeout) a WARN is logged **once**
 * and {@code List.of()} is returned: that agent degrades, the app keeps running.
 */
public final class McpToolSource implements ToolSource {

	private static final Logger log = LoggerFactory.getLogger(McpToolSource.class);
	private static final String SPRING_PREFIX = "spring_ai_mcp_client_";

	private final String serverName;
	private final List<ToolCallback> callbacks;
	private final AtomicBoolean warned = new AtomicBoolean();
	private volatile List<ToolCallback> cache;

	public McpToolSource(String serverName, ToolCallbackProvider provider) {
		this(serverName, provider == null ? List.of() : List.of(provider.getToolCallbacks()));
	}

	/** Manual wiring / test: accepts a callback list obtained from {@link McpToolUtils}. */
	public McpToolSource(String serverName, List<ToolCallback> callbacks) {
		this.serverName = serverName;
		this.callbacks = List.copyOf(callbacks);
	}

	@Override
	public String id() {
		return serverName;
	}

	@Override
	public ProviderKind provider() {
		return ProviderKind.MCP;
	}

	@Override
	public List<ToolCallback> toolCallbacks() {
		List<ToolCallback> current = cache;
		if (current == null) {
			current = load();
			cache = current;
		}
		return current;
	}

	@Override
	public boolean available() {
		return !toolCallbacks().isEmpty();
	}

	public void refresh() {
		cache = load();
	}

	private List<ToolCallback> load() {
		try {
			List<ToolCallback> own = callbacks.stream().filter(this::belongsToServer).toList();
			log.info("MCP source '{}' provided {} tools: {}", serverName, own.size(),
					own.stream().map(cb -> cb.getToolDefinition().name()).toList());
			return own.stream().map(this::stripInfrastructurePrefix).toList();
		} catch (RuntimeException ex) {
			if (warned.compareAndSet(false, true)) {
				log.warn("MCP server '{}' is not ready ({}); agents using these tools will run without them. "
						+ "Enable the server and restart to use them.", serverName, ex.getMessage());
			}
			return List.of();
		}
	}

	/** Callback provided by this server — drops tools from other connections (if any). */
	private boolean belongsToServer(ToolCallback callback) {
		String name = callback.getToolDefinition().name();
		String connectionTag = normalized(serverName) + "_";
		boolean otherServerPrefixed = callbacks.stream()
				.map(cb -> cb.getToolDefinition().name())
				.anyMatch(n -> n.startsWith(SPRING_PREFIX) && !n.startsWith(SPRING_PREFIX + connectionTag));
		if (!otherServerPrefixed) {
			return true;
		}
		return name.startsWith(SPRING_PREFIX + connectionTag) || name.startsWith(connectionTag);
	}

	private ToolCallback stripInfrastructurePrefix(ToolCallback callback) {
		String raw = callback.getToolDefinition().name();
		String tag = SPRING_PREFIX + normalized(serverName) + "_";
		String cleaned = raw.startsWith(tag) ? raw.substring(tag.length())
				: raw.startsWith(normalized(serverName) + "_") ? raw.substring(normalized(serverName).length() + 1)
						: raw;
		return cleaned.equals(raw) ? callback
				: new RenamedToolCallback(callback, cleaned);
	}

	private static String normalized(String value) {
		return value.replace('-', '_');
	}

	/** Only renames what the LLM sees; delegates args/context to the original callback. */
	private record RenamedToolCallback(ToolCallback delegate, String name) implements ToolCallback {

		@Override
		public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
			var source = delegate.getToolDefinition();
			return org.springframework.ai.tool.definition.ToolDefinition.builder()
					.name(name)
					.description(source.description())
					.inputSchema(source.inputSchema())
					.build();
		}

		@Override
		public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
			return delegate.getToolMetadata();
		}

		@Override
		public String call(String toolInput) {
			return delegate.call(toolInput);
		}

		@Override
		public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
			return delegate.call(toolInput, toolContext);
		}
	}
}

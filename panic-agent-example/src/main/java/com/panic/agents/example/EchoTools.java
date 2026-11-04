package com.panic.agents.example;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tools of the example agent. Tool names are prefixed with the agent id by the runtime
 * ({@code echo__echo}), so the LLM never sees collisions between agents.
 */
@Component
public class EchoTools {

	@Tool(description = "Echo the given text back unchanged.")
	public String echo(@ToolParam(description = "Text to echo") String text) {
		return text;
	}
}

package com.panic.core.agent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Fake ChatModel: each turn returns the next scripted step and runs the tool loop like a real ChatModel,
 * invoking the {@link ToolCallback} found in the prompt options (the ones the executor mounted).
 * Records each turn's prompt so tests can assert on the runtime-rendered system prompt.
 */
final class ScriptedChatModel implements ChatModel {

	private static final int MAX_TOOL_ROUNDS = 8;

	private final List<Function<Prompt, ChatResponse>> steps = new ArrayList<>();
	private final List<Prompt> prompts = new ArrayList<>();

	ScriptedChatModel reply(String text) {
		steps.add(prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
		return this;
	}

	ScriptedChatModel callTool(String name, String arguments) {
		steps.add(prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("call tool " + name, Map.of(),
				List.of(new AssistantMessage.ToolCall("call-" + name, "function", name, arguments)))))));
		return this;
	}

	List<Prompt> prompts() {
		return prompts;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		List<Message> history = new ArrayList<>(prompt.getInstructions());
		ToolCallingChatOptions options = prompt.getOptions() instanceof ToolCallingChatOptions t ? t : null;
		for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
			Prompt current = new Prompt(history, prompt.getOptions());
			prompts.add(current);
			ChatResponse response = next(current);
			AssistantMessage output = response.getResult() == null ? null : response.getResult().getOutput();
			if (output == null || output.getToolCalls() == null || output.getToolCalls().isEmpty()) {
				return response;
			}
			history.add(output);
			List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
			for (AssistantMessage.ToolCall call : output.getToolCalls()) {
				ToolCallback callback = find(options, call.name());
				String result = callback.call(call.arguments());
				toolResponses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
			}
			history.add(new ToolResponseMessage(toolResponses));
		}
		throw new IllegalStateException("Exceeded the allowed number of tool turns in the test");
	}

	private ToolCallback find(ToolCallingChatOptions options, String name) {
		if (options == null || options.getToolCallbacks() == null) {
			throw new IllegalStateException("No tool was mounted: " + name);
		}
		return options.getToolCallbacks().stream()
				.filter(cb -> cb.getToolDefinition().name().equals(name))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Tool not found " + name
						+ " among " + options.getToolCallbacks().stream()
								.map(cb -> cb.getToolDefinition().name()).toList()));
	}

	private ChatResponse next(Prompt prompt) {
		int index = prompts.size() - 1;
		if (index >= steps.size()) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("(script exhausted)"))));
		}
		return steps.get(index).apply(prompt);
	}
}

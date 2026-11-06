package com.panic.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

/**
 * Wraps an LLM call (coordinator or domain agent) and records the pipeline:
 * the prompt sent to the model, tools the LLM decides to call, tool results, the final answer.
 * <p>
 * Implements both {@link CallAdvisor} and {@link StreamAdvisor} so it records identically whether
 * the answer is requested blocking ({@code call()}) or streamed ({@code stream()}). On a stream the
 * chunks are aggregated and one LLM event is recorded when the flux completes.
 */
public final class LlmCallAdvisor implements CallAdvisor, StreamAdvisor {

	private final String name;
	private final PipelineRecorder recorder;
	private final PipelineRecorder.RequestTrace trace;
	private final Long parentId;
	private final String agentId;

	public LlmCallAdvisor(String name, PipelineRecorder recorder, PipelineRecorder.RequestTrace trace, Long parentId) {
		this(name, recorder, trace, parentId, null);
	}

	/** {@code agentId} null = event not tied to an agent (e.g. an agent run outside the executor). */
	public LlmCallAdvisor(String name, PipelineRecorder recorder, PipelineRecorder.RequestTrace trace, Long parentId,
			String agentId) {
		this.name = name;
		this.recorder = recorder;
		this.trace = trace;
		this.parentId = parentId;
		this.agentId = agentId;
	}

	/** An agent's LLM call always carries agentId + depth taken from the running agent. */
	public static LlmCallAdvisor forAgent(String agentId, PipelineRecorder recorder,
			PipelineRecorder.RequestTrace trace, Long parentId) {
		return new LlmCallAdvisor(agentId, recorder, trace, parentId, agentId);
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		PipelineEvent event = start(request);
		long began = System.currentTimeMillis();
		ChatClientResponse response;
		try {
			response = chain.nextCall(request);
		} catch (RuntimeException ex) {
			recorder.finish(trace, event, PipelineEvent.STATUS_ERROR, "LLM call · " + name,
					PipelineJson.of(Map.of("error", String.valueOf(ex.getMessage()))),
					System.currentTimeMillis() - began);
			throw ex;
		}
		finish(event, response, System.currentTimeMillis() - began);
		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		PipelineEvent event = start(request);
		long began = System.currentTimeMillis();
		List<ChatClientResponse> chunks = new ArrayList<>();
		return chain.nextStream(request)
				.doOnNext(chunks::add)
				.doOnComplete(() -> finish(event, aggregate(chunks), System.currentTimeMillis() - began))
				.doOnError(error -> recorder.finish(trace, event, PipelineEvent.STATUS_ERROR,
						"LLM call · " + name,
						PipelineJson.of(Map.of("error", String.valueOf(error.getMessage()))),
						System.currentTimeMillis() - began));
	}

	private PipelineEvent start(ChatClientRequest request) {
		String prompt = request.prompt() == null ? "" : request.prompt().getContents();
		String resolvedAgent = agentId != null ? agentId
				: recorder.activeAgent() == null ? null : recorder.activeAgent().agentId();
		return recorder.start(trace, PipelineEvent.PHASE_LLM, name, "LLM call · " + name,
				PipelineJson.of(Map.of("prompt", prompt)), parentId, resolvedAgent);
	}

	private void finish(PipelineEvent event, ChatClientResponse response, long duration) {
		List<ToolCall> calls = toolCalls(response);
		String text = textOf(response);
		String summary = !calls.isEmpty()
				? "Chose tools: " + calls.stream().map(ToolCall::name).collect(Collectors.joining(", "))
				: text.isBlank() ? "No content" : "Answered directly";
		recorder.finish(trace, event, PipelineEvent.STATUS_OK, "LLM call · " + name + " → " + summary,
				PipelineJson.of(Map.of("toolCalls", calls.stream().map(ToolCall::name).toList(), "text", text)),
				duration);
	}

	/** Combine the streamed chunks into one response for the recorded event (text + tool calls). */
	private static ChatClientResponse aggregate(List<ChatClientResponse> chunks) {
		if (chunks.isEmpty()) {
			return null;
		}
		StringBuilder text = new StringBuilder();
		List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
		for (ChatClientResponse chunk : chunks) {
			ChatResponse chatResponse = chunk.chatResponse();
			if (chatResponse == null) {
				continue;
			}
			Generation generation = chatResponse.getResult();
			AssistantMessage output = generation == null ? null : generation.getOutput();
			if (output == null) {
				continue;
			}
			if (output.getText() != null) {
				text.append(output.getText());
			}
			if (output.getToolCalls() != null) {
				toolCalls.addAll(output.getToolCalls());
			}
		}
		AssistantMessage combined = new AssistantMessage(text.toString(), Map.of(), toolCalls);
		return new ChatClientResponse(new ChatResponse(List.of(new Generation(combined))),
				chunks.get(chunks.size() - 1).context());
	}

	static List<ToolResponseMessage.ToolResponse> toolResponses(ChatClientRequest request) {
		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		if (request.prompt() == null) {
			return responses;
		}
		for (var message : request.prompt().getInstructions()) {
			if (message instanceof ToolResponseMessage toolMessage) {
				responses.addAll(toolMessage.getResponses());
			}
		}
		return responses;
	}

	static List<ToolCall> toolCalls(ChatClientResponse response) {
		List<ToolCall> calls = new ArrayList<>();
		ChatResponse chatResponse = response == null ? null : response.chatResponse();
		if (chatResponse == null) {
			return calls;
		}
		for (Generation generation : chatResponse.getResults()) {
			AssistantMessage output = generation == null ? null : generation.getOutput();
			if (output == null || output.getToolCalls() == null) {
				continue;
			}
			output.getToolCalls().forEach(call -> calls.add(new ToolCall(call.name(), String.valueOf(call.arguments()))));
		}
		return calls;
	}

	static String textOf(ChatClientResponse response) {
		ChatResponse chatResponse = response == null ? null : response.chatResponse();
		if (chatResponse == null) {
			return "";
		}
		Generation generation = chatResponse.getResult();
		AssistantMessage output = generation == null ? null : generation.getOutput();
		return output == null ? "" : output.getText();
	}

	record ToolCall(String name, String arguments) {
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public int getOrder() {
		return 0;
	}
}

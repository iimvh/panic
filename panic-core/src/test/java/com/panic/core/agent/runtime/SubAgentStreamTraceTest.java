package com.panic.core.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.panic.core.agent.spi.AgentDefinition;
import com.panic.core.agent.spi.AgentResult;
import com.panic.core.agent.spi.PromptRepository;
import com.panic.core.agent.spi.Skill;
import com.panic.core.agent.spi.SkillRepository;
import com.panic.pipeline.PipelineBroadcaster;
import com.panic.pipeline.PipelineEvent;
import com.panic.pipeline.PipelineRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The streamed answer of the root agent runs its tool loop inside Spring AI's reactive pipeline,
 * so tool callbacks execute on reactor threads. This test drives that exact path with a
 * wire-level fake of the OpenAI API (SSE over WebClient, JSON over RestClient) and asserts the
 * pipeline still records the sub-agent's tool event, its LLM call and its running-agent node.
 */
class SubAgentStreamTraceTest {

	private static final long CREATED = 1_760_000_000_000L;

	@Test
	void streamedToolRoundStillRecordsSubAgentEvents() {
		OpenAiChatModel model = transport().model();
		PipelineRecorder recorder = new PipelineRecorder(new PipelineBroadcaster());
		AgentRegistry registry = new AgentRegistry(List.of(coordinator("echo"), echoAgent()), "coordinator");
		AgentExecutor executor = new AgentExecutor(ChatClient.builder(model), registry, noSkills(), recorder,
				new AgentPromptRenderer(location -> Optional.empty()), null, new PipelineBroadcaster(), "test-key");
		PipelineRecorder.RequestTrace trace = new PipelineRecorder.RequestTrace();
		recorder.startResponse(trace);
		recorder.bind(trace);

		AgentResult result = executor.runRoot("gọi echo 3 lần");

		PipelineEvent[] rawEvents = recorder.end(trace, false, null);
		recorder.unbind();

		assertThat(result.text()).isEqualTo("Đã gọi echo xong");

		List<PipelineEvent> events = List.of(rawEvents);

		PipelineEvent toolEvent = events.stream()
				.filter(event -> "tool".equals(event.phase()) && "Sub-agent echo".equals(event.title()))
				.findFirst().orElse(null);
		assertThat(toolEvent).as("sub-agent tool run must be recorded even when the streamed tool loop "
				+ "executes on a reactor thread").isNotNull();
		assertThat(events).anyMatch(event -> "llm".equals(event.phase()) && "echo".equals(event.agentId()));
		assertThat(events).anyMatch(event -> "trace".equals(event.phase()) && "echo".equals(event.agentId())
				&& Integer.valueOf(1).equals(event.depth()));
	}

	private static AgentDefinition coordinator(String... subs) {
		return AgentDefinition.builder("coordinator")
				.description("Root agent")
				.subAgentIds(subs)
				.systemPromptTemplate("Sub-agents:\n{subAgents}\n\nTools:\n{tools}\n\nNow: {now}")
				.build();
	}

	private static AgentDefinition echoAgent() {
		return AgentDefinition.builder("echo")
				.description("Echoes a short text back to the caller.")
				.systemPromptTemplate("You are an echo agent. Skills:\n{skills}")
				.build();
	}

	private static SkillRepository noSkills() {
		return new SkillRepository() {
			@Override
			public List<Skill> list() {
				return List.of();
			}

			@Override
			public Optional<Skill> get(String id) {
				return Optional.empty();
			}
		};
	}

	/**
	 * Wire-level fake: streamed requests go through a WebClient exchange function that emits
	 * OpenAI SSE frames on a non-caller thread (like reactor-netty does), blocking requests get
	 * a canned JSON completion through a buffering ClientHttpRequestFactory.
	 */
	private static MockOpenAiTransport transport() {
		return new MockOpenAiTransport();
	}

	private static final class MockOpenAiTransport implements ExchangeFunction, ClientHttpRequestFactory {

		private final AtomicInteger streamingRounds = new AtomicInteger();
		OpenAiChatModel model() {
			OpenAiApi api = OpenAiApi.builder()
					.baseUrl("http://mock.local")
					.apiKey("test-key")
					.restClientBuilder(RestClient.builder().requestFactory(this))
					.webClientBuilder(WebClient.builder().exchangeFunction(this))
					.build();
			return OpenAiChatModel.builder()
					.openAiApi(api)
					.defaultOptions(OpenAiChatOptions.builder().model("mock-model").temperature(0.1).build())
					.toolCallingManager(DefaultToolCallingManager.builder().build())
					.observationRegistry(ObservationRegistry.NOOP)
					.retryTemplate(RetryTemplate.builder().build())
					.build();
		}

		@Override
		public Mono<ClientResponse> exchange(ClientRequest request) {
			int round = streamingRounds.incrementAndGet();
			return Mono.just(streamedResponse(round));
		}

		private ClientResponse streamedResponse(int round) {
			boolean toolResultSeen = round > 1;
			List<String> payloads = toolResultSeen
					? List.of(textChunk("Đã gọi"), textChunk(" echo xong"), finishStopRound(), "[DONE]")
					: List.of(toolCallRound(), finishToolCallRound(), "[DONE]");
			Flux<DataBuffer> body = Flux.fromIterable(payloads)
					.map(payload -> "data: " + payload.replace("\r", "").replace("\n", "").strip() + "\n\n")
					.map(this::buffer)
					.publishOn(Schedulers.boundedElastic());
			return ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(body)
					.build();
		}

		private DataBuffer buffer(String line) {
			return DefaultDataBufferFactory.sharedInstance.wrap(line.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
			MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
			MockClientHttpResponse response = new MockClientHttpResponse(
					blockingCompletion().getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
			response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			request.setResponse(response);
			return request;
		}

		private String blockingCompletion() {
			return """
					{"id":"cmpl-blk","object":"chat.completion","created":1760000000000,"model":"mock-model",
					"choices":[{"index":0,"message":{"role":"assistant","content":"Echo ket qua xong"},
					"finish_reason":"stop","logprobs":null}],
					"usage":{"prompt_tokens":1,"completion_tokens":3,"total_tokens":4}}""";
		}

		private String toolCallRound() {
			return """
					{"id":"cmpl-1","object":"chat.completion.chunk","created":1760000000000,"model":"mock-model",
					"choices":[
						{"index":0,"delta":{"role":"assistant","tool_calls":[
							{"index":0,"id":"c1","type":"function","function":{"name":"call_echo","arguments":"{\\"query\\":\\"xin chao\\"}"}}
						]},"logprobs":null,"finish_reason":null}
					]}""";
		}

		private String finishToolCallRound() {
			return """
					{"id":"cmpl-1","object":"chat.completion.chunk","created":1760000000000,"model":"mock-model",
					"choices":[{"index":0,"delta":{},"logprobs":null,"finish_reason":"tool_calls"}]}""";
		}

		private String textChunk(String content) {
			return "{\"id\":\"cmpl-2\",\"object\":\"chat.completion.chunk\",\"created\":1760000000000,"
					+ "\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content
					+ "\"},\"logprobs\":null,\"finish_reason\":null}]}";
		}

		private String finishStopRound() {
			return """
					{"id":"cmpl-2","object":"chat.completion.chunk","created":1760000000000,"model":"mock-model",
					"choices":[{"index":0,"delta":{},"logprobs":null,"finish_reason":"stop"}]}""";
		}
	}
}

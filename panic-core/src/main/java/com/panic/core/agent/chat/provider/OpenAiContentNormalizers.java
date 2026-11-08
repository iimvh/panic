package com.panic.core.agent.chat.provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Transport-level workarounds that make Spring AI 1.0.0 tolerate providers whose
 * {@code content} is an array (see {@link OpenAiContentNormalizer}). Wired into the OpenAI client by
 * {@link RuntimeLlmProviderChatFactory} so every chat call — blocking and streaming — is normalized
 * before Spring AI parses it, without patching the library or restricting the model.
 */
final class OpenAiContentNormalizers {

	private static final Logger log = LoggerFactory.getLogger(OpenAiContentNormalizers.class);

	private OpenAiContentNormalizers() {
	}

	/** Blocking interceptor: rewrite the response body, keep status/headers. */
	static ClientHttpRequestInterceptor restInterceptor() {
		return (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
			ClientHttpResponse response = execution.execute(request, body);
			byte[] original = readAll(response);
			String normalized = OpenAiContentNormalizer.normalizeBody(
					new String(original, StandardCharsets.UTF_8));
			return new WrappedResponse(response, normalized.getBytes(StandardCharsets.UTF_8));
		};
	}

	/** Streaming filter: normalize the SSE body incrementally, preserving token-by-token delivery. */
	static ExchangeFilterFunction webFilter() {
		return (request, next) -> next.exchange(request)
				.map(response -> response.mutate()
						.body(SseContentNormalizer::normalize)
						.build());
	}

	private static byte[] readAll(ClientHttpResponse response) throws IOException {
		byte[] bytes = response.getBody().readAllBytes();
		return bytes.length == 0 ? bytes : bytes;
	}

	/** A response whose body has already been read and replaced, so callers see the rewrite. */
	private static final class WrappedResponse implements ClientHttpResponse {

		private final ClientHttpResponse delegate;
		private final byte[] body;

		WrappedResponse(ClientHttpResponse delegate, byte[] body) {
			this.delegate = delegate;
			this.body = body;
		}

		@Override
		public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
			return delegate.getStatusCode();
		}

		@Override
		public String getStatusText() throws IOException {
			return delegate.getStatusText();
		}

		@Override
		public void close() {
			delegate.close();
		}

		@Override
		public java.io.InputStream getBody() {
			return new ByteArrayInputStream(body);
		}

		@Override
		public org.springframework.http.HttpHeaders getHeaders() {
			return delegate.getHeaders();
		}
	}
}

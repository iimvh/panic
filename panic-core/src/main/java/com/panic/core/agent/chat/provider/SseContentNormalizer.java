package com.panic.core.agent.chat.provider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;

/**
 * Turns a raw SSE {@code DataBuffer} stream into one where each complete event's {@code data:} JSON
 * has array-shaped {@code content} rewritten to a string, while still emitting incrementally.
 * <p>
 * Spring AI reads the stream event by event; a buffer may split an event in two, so bytes are
 * buffered until the event terminator ({@code \n\n}) is seen, normalized, then released. {@code
 * concatMap} keeps processing serial per subscription, so the shared pending buffer is safe. This
 * keeps token streaming intact — only the {@code content} shape changes.
 */
final class SseContentNormalizer {

	private static final DefaultDataBufferFactory FACTORY = DefaultDataBufferFactory.sharedInstance;
	private static final String EVENT_TERMINATOR = "\n\n";

	private SseContentNormalizer() {
	}

	static Flux<DataBuffer> normalize(Flux<DataBuffer> body) {
		return Flux.defer(() -> {
			StringBuilder pending = new StringBuilder();
			return body.concatMap(buffer -> releaseCompleteEvents(pending, buffer))
					.concatWith(Flux.defer(() -> flush(pending)));
		});
	}

	/** Appends the buffer to the tail, then emits every complete event it now contains. */
	private static Flux<DataBuffer> releaseCompleteEvents(StringBuilder pending, DataBuffer buffer) {
		pending.append(read(buffer));
		List<DataBuffer> events = new ArrayList<>();
		int end;
		while ((end = pending.indexOf(EVENT_TERMINATOR)) >= 0) {
			String event = pending.substring(0, end + EVENT_TERMINATOR.length());
			pending.delete(0, end + EVENT_TERMINATOR.length());
			events.add(wrap(event));
		}
		return Flux.fromIterable(events);
	}

	private static Flux<DataBuffer> flush(StringBuilder pending) {
		if (pending.length() == 0) {
			return Flux.empty();
		}
		String tail = pending.toString();
		pending.setLength(0);
		return Flux.just(wrap(tail));
	}

	private static DataBuffer wrap(String event) {
		String normalized = OpenAiContentNormalizer.normalizeBody(event);
		return FACTORY.wrap(normalized.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(DataBuffer buffer) {
		try {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			return new String(bytes, StandardCharsets.UTF_8);
		} finally {
			DataBufferUtils.release(buffer);
		}
	}
}

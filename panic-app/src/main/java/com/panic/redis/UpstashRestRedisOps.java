package com.panic.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.panic.core.redis.StringRedisOps;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Drives an Upstash Redis database over its REST API: every command travels as one POST
 * carrying the Redis command as a JSON array, answers come back as {@code {"result": …}}
 * or {@code {"error": …}}. Pipelining keeps the fixed-window quota at a single round trip.
 * Throwing here is fine — callers wrap this adapter in the fail-open guard.
 */
public class UpstashRestRedisOps implements StringRedisOps {

	private final RestClient rest;
	private final String endpoint;

	public UpstashRestRedisOps(String restUrl, String token) {
		this.endpoint = restUrl.strip().replaceAll("/+$", "");
		this.rest = RestClient.builder()
				.baseUrl(this.endpoint)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	@Override
	public Optional<String> get(String key) {
		JsonNode result = execute(List.of("GET", key));
		return result.isNull() ? Optional.empty() : Optional.of(result.asText());
	}

	@Override
	public void set(String key, String value, Duration ttl) {
		execute(List.of("SET", key, value, "EX", seconds(ttl)));
	}

	@Override
	public boolean setIfAbsent(String key, String value, Duration ttl) {
		// SET … NX answers "OK" when the entry was created, null when somebody else holds it.
		JsonNode result = execute(List.of("SET", key, value, "NX", "EX", seconds(ttl)));
		return !result.isNull() && "OK".equalsIgnoreCase(result.asText());
	}

	@Override
	public long incrementWindow(String key, Duration window) {
		// [[INCR k], [TTL k]] in one round trip; TTL repairs a window whose expiry was lost.
		List<JsonNode> results = executeAll(List.of(
				List.of("INCR", key),
				List.of("TTL", key)));
		long hits = asLong(results.get(0));
		long ttl = asLong(results.get(1));
		if (hits == 1 || ttl == -1) {
			execute(List.of("EXPIRE", key, seconds(window)));
		}
		return hits;
	}

	@Override
	public void delete(String key) {
		execute(List.of("DEL", key));
	}

	/** One command; anything the server reports as an error becomes an exception. */
	private JsonNode execute(List<String> command) {
		JsonNode answer = rest.post()
				.body(command)
				.retrieve()
				.body(JsonNode.class);
		return check(answer);
	}

	private List<JsonNode> executeAll(List<List<String>> commands) {
		JsonNode answer = rest.post()
				.uri("/pipeline")
				.body(commands)
				.retrieve()
				.body(JsonNode.class);
		if (answer == null || !answer.isArray()) {
			throw new IllegalStateException("Upstash REST pipeline returned no results");
		}
		return StreamSupport.stream(answer.spliterator(), false)
				.map(UpstashRestRedisOps::check)
				.toList();
	}

	private static JsonNode check(JsonNode answer) {
		if (answer == null || answer.has("error")) {
			String error = answer == null ? "empty response" : answer.path("error").asText();
			throw new IllegalStateException("Upstash REST error: " + error);
		}
		return answer.path("result");
	}

	/** Redis args travel as strings on the REST protocol (numbers included). */
	private static String seconds(Duration ttl) {
		return String.valueOf(Math.max(1, ttl.toSeconds()));
	}

	private static long asLong(JsonNode result) {
		return result == null ? 0 : result.asLong();
	}
}

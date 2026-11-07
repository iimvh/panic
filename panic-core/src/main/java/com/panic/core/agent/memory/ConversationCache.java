package com.panic.core.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import com.panic.core.redis.StringRedisOps;

/**
 * Redis hot cache of a conversation's recent-turn window ({@code panic:conv:{id}:window}): the
 * exact turns {@link ConversationStore#loadMessages} replays into the root agent's prompt.
 * Purely an accelerator — a cache miss simply falls through to JPA, and without Redis at all
 * (bean absent or unreachable) every call is a no-op.
 */
@Component
public class ConversationCache {

	private static final Logger log = LoggerFactory.getLogger(ConversationCache.class);

	private static final String WINDOW_KEY = "panic:conv:%s:window";

	private final StringRedisOps redis;
	private final Duration ttl;
	private final ObjectMapper json = new ObjectMapper();

	public ConversationCache(@Nullable StringRedisOps redis,
			@Value("${app.conversation.window-cache-ttl-seconds:600}") long ttlSeconds) {
		this.redis = redis;
		this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
	}

	/** Cached window (oldest first), or empty on miss / absent Redis / unreadable payload. */
	public Optional<List<CachedTurn>> loadWindow(String conversationId) {
		if (redis == null) {
			return Optional.empty();
		}
		String key = key(conversationId);
		return redis.get(key).flatMap(raw -> {
			List<CachedTurn> parsed = parse(raw);
			if (parsed == null) {
				return Optional.empty();
			}
			// TTL-refreshed: a conversation still being read stays hot a window longer.
			redis.set(key, raw, ttl);
			return Optional.of(parsed);
		});
	}

	/**
	 * Write-through after an append: the cached window (if used) always mirrors the persisted one.
	 * Windows are also (re)populated lazily on the first cache miss.
	 */
	public void storeWindow(String conversationId, List<CachedTurn> turns) {
		if (redis == null || turns.isEmpty()) {
			return;
		}
		try {
			redis.set(key(conversationId), json.writeValueAsString(turns), ttl);
		} catch (Exception ex) {
			log.debug("Conversation window cache write failed for '{}': {}", conversationId, ex.getMessage());
		}
	}

	/** Drop the cached window (conversation deleted or its memory must not be reused). */
	public void invalidate(String conversationId) {
		if (redis != null) {
			redis.delete(key(conversationId));
		}
	}

	private List<CachedTurn> parse(String raw) {
		try {
			return json.readValue(raw, json.getTypeFactory().constructCollectionType(List.class, CachedTurn.class));
		} catch (Exception ex) {
			log.debug("Discarding unreadable cached window: {}", ex.getMessage());
			return null;
		}
	}

	private static String key(String conversationId) {
		return WINDOW_KEY.formatted(conversationId);
	}

	/** One turn of the window: the two fields the agent prompt rebuild needs. */
	public record CachedTurn(ChatTurn.Role role, String content) {
	}
}

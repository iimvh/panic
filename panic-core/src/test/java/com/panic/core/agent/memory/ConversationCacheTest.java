package com.panic.core.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.panic.core.redis.InMemoryStringRedisOps;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The recent-turn hot window: round trip, TTL refresh, invalidation, and no-Redis mode. */
class ConversationCacheTest {

	private static final List<ConversationCache.CachedTurn> WINDOW = List.of(
			new ConversationCache.CachedTurn(ChatTurn.Role.USER, "hello"),
			new ConversationCache.CachedTurn(ChatTurn.Role.ASSISTANT, "hi there"));

	@Test
	void storedWindowLoadsBackInOrder() {
		InMemoryStringRedisOps redis = new InMemoryStringRedisOps();
		ConversationCache cache = new ConversationCache(redis, 600);
		cache.storeWindow("conv-1", WINDOW);

		assertThat(cache.loadWindow("conv-1")).contains(WINDOW);
	}

	@Test
	void loadingRefreshesTheTtl() {
		InMemoryStringRedisOps redis = new InMemoryStringRedisOps();
		ConversationCache cache = new ConversationCache(redis, 600);
		cache.storeWindow("conv-1", WINDOW);
		redis.advance(Duration.ofSeconds(590));

		// A read just before the 600s TTL arm refreshes the window for another 600s…
		assertThat(cache.loadWindow("conv-1")).contains(WINDOW);
		// …so it is still hot well past the original expiry.
		redis.advance(Duration.ofSeconds(585));
		assertThat(cache.loadWindow("conv-1")).contains(WINDOW);
	}

	@Test
	void untouchedWindowExpires() {
		InMemoryStringRedisOps redis = new InMemoryStringRedisOps();
		ConversationCache cache = new ConversationCache(redis, 600);
		cache.storeWindow("conv-1", WINDOW);
		redis.advance(Duration.ofSeconds(601));
		assertThat(cache.loadWindow("conv-1")).isEmpty();
	}

	@Test
	void unknownOrInvalidatedWindowMisses() {
		InMemoryStringRedisOps redis = new InMemoryStringRedisOps();
		ConversationCache cache = new ConversationCache(redis, 600);
		assertThat(cache.loadWindow("conv-1")).isEmpty();
		cache.storeWindow("conv-1", WINDOW);
		cache.invalidate("conv-1");
		assertThat(cache.loadWindow("conv-1")).isEmpty();
		assertThat(redis.peek("panic:conv:conv-1:window")).isNull();
	}

	@Test
	void unreadablePayloadIsAMissNotAnError() {
		InMemoryStringRedisOps redis = new InMemoryStringRedisOps();
		ConversationCache cache = new ConversationCache(redis, 600);
		redis.set("panic:conv:conv-1:window", "{not json", Duration.ofSeconds(60));
		assertThat(cache.loadWindow("conv-1")).isEqualTo(Optional.empty());
	}

	@Test
	void withoutRedisEveryCallIsANoop() {
		ConversationCache cache = new ConversationCache(null, 600);
		assertThat(cache.loadWindow("conv-1")).isEmpty();
		cache.storeWindow("conv-1", WINDOW);
		cache.invalidate("conv-1");
		assertThat(cache.loadWindow("conv-1")).isEmpty();
	}

	@Test
	void emptyWindowsAreNotStored() {
		InMemoryStringRedisOps redis = new InMemoryStringRedisOps();
		ConversationCache cache = new ConversationCache(redis, 600);
		cache.storeWindow("conv-1", List.of());
		assertThat(cache.loadWindow("conv-1")).isEmpty();
	}
}

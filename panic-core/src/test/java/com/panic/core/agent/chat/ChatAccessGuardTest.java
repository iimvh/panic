package com.panic.core.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.panic.core.redis.InMemoryStringRedisOps;
import com.panic.exception.BookingException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The conversation write guards, tested entirely through the StringRedisOps port fake. */
class ChatAccessGuardTest {

	private InMemoryStringRedisOps redis;
	private ChatAccessGuard guard;

	@BeforeEach
	void setUp() {
		redis = new InMemoryStringRedisOps();
		guard = new ChatAccessGuard(redis, 3, 2, 300);
	}

	@Test
	void conversationQuotaRejectsBeyondLimitWith429() {
		// Released between sends, like the chat path does: each admission re-takes the lock,
		// so only the quota is what can trip on the fourth send in one minute.
		for (int i = 0; i < 3; i++) {
			assertThatCode(() -> guard.admitChat("conv-1")).doesNotThrowAnyException();
			guard.releaseChat("conv-1");
		}
		assertThatThrownBy(() -> guard.admitChat("conv-1"))
				.isInstanceOf(BookingException.class)
				.hasMessageContaining("Too many messages")
				.satisfies(ex -> assertThat(((BookingException) ex).status()).isEqualTo(429));
	}

	@Test
	void quotasArePerConversation() {
		guard.admitChat("conv-1");
		guard.admitChat("conv-2");
		assertThat(redis.peek(ChatAccessGuard.CHAT_QUOTA_KEY.formatted("conv-1")).value())
				.isEqualTo("1");
	}

	@Test
	void differentConversationsDoNotBlockEachOther() {
		guard.acquireLock("conv-1");
		assertThatCode(() -> guard.acquireLock("conv-2")).doesNotThrowAnyException();
	}

	@Test
	void aSecondRunOnTheSameConversationIsBlocked() {
		guard.admitChat("conv-1");
		assertThatThrownBy(() -> guard.admitChat("conv-1"))
				.isInstanceOf(BookingException.class)
				.hasMessageContaining("already working on a task")
				.satisfies(ex -> assertThat(((BookingException) ex).status()).isEqualTo(409));
	}

	@Test
	void releasingTheLockLetsTheNextRunIn() {
		guard.admitChat("conv-1");
		guard.releaseChat("conv-1");
		assertThatCode(() -> guard.admitChat("conv-1")).doesNotThrowAnyException();
	}

	@Test
	void lockExpiresAfterTtlSoARunCanNeverDeadlockForever() {
		guard.acquireLock("conv-1");
		redis.advance(Duration.ofSeconds(301));
		assertThatCode(() -> guard.acquireLock("conv-1")).doesNotThrowAnyException();
	}

	@Test
	void runningConversationIsNotIdleForMutations() {
		assertThatCode(() -> guard.assertConversationIdle("conv-1")).doesNotThrowAnyException();
		guard.acquireLock("conv-1");
		assertThatThrownBy(() -> guard.assertConversationIdle("conv-1"))
				.isInstanceOf(BookingException.class)
				.satisfies(ex -> assertThat(((BookingException) ex).status()).isEqualTo(409));
		guard.releaseChat("conv-1");
		assertThatCode(() -> guard.assertConversationIdle("conv-1")).doesNotThrowAnyException();
	}

	@Test
	void sessionCreationQuotaRejectsBeyondLimitWith429() {
		guard.admitSessionCreation("local");
		guard.admitSessionCreation("local");
		assertThatThrownBy(() -> guard.admitSessionCreation("local"))
				.isInstanceOf(BookingException.class)
				.hasMessageContaining("Too many new chats")
				.satisfies(ex -> assertThat(((BookingException) ex).status()).isEqualTo(429));
	}

	@Test
	void withoutRedisEverythingIsAdmitted() {
		ChatAccessGuard bare = new ChatAccessGuard(null, 1, 1, 1);
		assertThatCode(() -> bare.admitChat("conv-1")).doesNotThrowAnyException();
		assertThatCode(() -> bare.admitSessionCreation("local")).doesNotThrowAnyException();
		assertThatCode(() -> bare.assertConversationIdle("conv-1")).doesNotThrowAnyException();
		assertThatCode(() -> bare.releaseChat("conv-1")).doesNotThrowAnyException();
	}
}

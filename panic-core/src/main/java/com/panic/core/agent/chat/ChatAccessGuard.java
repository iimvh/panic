package com.panic.core.agent.chat;

import com.panic.core.redis.StringRedisOps;
import com.panic.exception.BookingException;
import java.net.HttpURLConnection;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Guards the per-conversation write paths of the chat API, entirely through Redis:
 * a fixed-window request quota per conversation ({@code panic:rl:{id}}), a per-user quota
 * for creating sessions ({@code panic:rl:new:{user}}) and a per-conversation run lock
 * ({@code panic:lock:{id}}) so the same conversation is never driven twice at once.
 * <p>
 * Redis is optional: with no adapter at all, or while one is unreachable (the fail-open
 * wrapper {@link com.panic.core.redis.FailOpenRedisOps} degrades to "allow"), every check
 * passes — availability of the workspace beats strictness of its quotas.
 */
@Component
public class ChatAccessGuard {

	static final String CHAT_QUOTA_KEY = "panic:rl:%s";
	static final String SESSION_QUOTA_KEY = "panic:rl:new:%s";
	static final String LOCK_KEY = "panic:lock:%s";

	private final StringRedisOps redis;
	private final int chatQuotaPerMinute;
	private final int sessionQuotaPerMinute;
	private final Duration lockTtl;

	public ChatAccessGuard(@Nullable StringRedisOps redis,
			@Value("${app.chat.rate-limit-per-minute:20}") int chatQuotaPerMinute,
			@Value("${app.chat.create-limit-per-minute:20}") int sessionQuotaPerMinute,
			@Value("${app.chat.lock-ttl-seconds:300}") int lockTtlSeconds) {
		this.redis = redis;
		this.chatQuotaPerMinute = chatQuotaPerMinute;
		this.sessionQuotaPerMinute = sessionQuotaPerMinute;
		this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
	}

	/**
	 * Admits one chat send to a known conversation: counts it against the conversation's
	 * per-minute quota (exceeded → 429) and acquires the run lock (already held → 409).
	 * Throws {@link BookingException} with the matching HTTP status on rejection.
	 */
	public void admitChat(String conversationId) {
		count(CHAT_QUOTA_KEY.formatted(conversationId), chatQuotaPerMinute,
				"Too many messages in this conversation right now; wait a moment and send again");
		acquireLock(conversationId);
	}

	/** Admits the creation of one new session against the per-user quota (exceeded → 429). */
	public void admitSessionCreation(String userId) {
		count(SESSION_QUOTA_KEY.formatted(userId), sessionQuotaPerMinute,
				"Too many new chats in the last minute; wait a moment and try again");
	}

	/** Takes the run lock of a conversation; throws 409 when another run already holds it. */
	public void acquireLock(String conversationId) {
		if (redis == null) {
			return;
		}
		boolean acquired = redis.setIfAbsent(LOCK_KEY.formatted(conversationId), "running", lockTtl);
		if (!acquired) {
			throw new BookingException("Conversation is already working on a task; wait for it to finish",
					HttpURLConnection.HTTP_CONFLICT);
		}
	}

	/** Frees the run lock when the worker thread finishes — in the finally path of a run. */
	public void releaseChat(String conversationId) {
		if (redis != null) {
			redis.delete(LOCK_KEY.formatted(conversationId));
		}
	}

	/**
	 * Rejects (409) mutating a conversation (rename/delete) while a task is driving it.
	 * Idle conversations, or Redis being absent, always pass.
	 */
	public void assertConversationIdle(String conversationId) {
		if (redis != null && redis.get(LOCK_KEY.formatted(conversationId)).isPresent()) {
			throw new BookingException("Conversation is currently running a task; try again once it finishes",
					HttpURLConnection.HTTP_CONFLICT);
		}
	}

	private void count(String key, int quota, String message) {
		if (redis == null) {
			return;
		}
		long hits = redis.incrementWindow(key, Duration.ofMinutes(1));
		if (hits > quota) {
			throw new BookingException(message, 429);
		}
	}
}

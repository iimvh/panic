package com.panic.redis;

import com.panic.core.redis.StringRedisOps;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * TCP fallback of the {@link StringRedisOps} port for environments that only expose a classic
 * Redis URL ({@code REDIS_URL}, {@code rediss://…} for TLS): wraps Spring's
 * {@link StringRedisTemplate} over a Lettuce connection factory built straight from the URL.
 * Used only when no Upstash REST credentials are configured (they are the preferred adapter).
 */
public class TcpRedisOps implements StringRedisOps, AutoCloseable {

	private final LettuceConnectionFactory connectionFactory;
	private final StringRedisTemplate redis;

	public TcpRedisOps(String redisUrl) {
		this.connectionFactory = connectionFactory(redisUrl);
		this.redis = new StringRedisTemplate(connectionFactory);
	}

	@Override
	public Optional<String> get(String key) {
		return Optional.ofNullable(redis.opsForValue().get(key));
	}

	@Override
	public void set(String key, String value, Duration ttl) {
		redis.opsForValue().set(key, value, ttl);
	}

	@Override
	public boolean setIfAbsent(String key, String value, Duration ttl) {
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl));
	}

	@Override
	public long incrementWindow(String key, Duration window) {
		Long hits = redis.opsForValue().increment(key);
		if (hits == null) {
			return 0;
		}
		// getExpire: -1 = key exists without expiry (a lost arm), positive = armed window.
		Long ttl = redis.getExpire(key);
		if (hits == 1 || (ttl != null && ttl == -1)) {
			redis.expire(key, window);
		}
		return hits;
	}

	@Override
	public void delete(String key) {
		redis.delete(key);
	}

	@Override
	public void close() {
		connectionFactory.destroy();
	}

	private static LettuceConnectionFactory connectionFactory(String redisUrl) {
		RedisURI uri = RedisURI.create(redisUrl.strip());
		RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
		server.setDatabase(uri.getDatabase());
		if (uri.getUsername() != null && !uri.getUsername().isBlank()) {
			server.setUsername(uri.getUsername());
		}
		if (uri.getPassword() != null && uri.getPassword().length > 0) {
			server.setPassword(RedisPassword.of(new String(uri.getPassword())));
		}
		LettuceClientConfiguration client = uri.isSsl()
				? LettuceClientConfiguration.builder().useSsl().build()
				: LettuceClientConfiguration.builder().build();
		LettuceConnectionFactory factory = new LettuceConnectionFactory(server, client);
		factory.start();
		return factory;
	}
}

package com.panic.redis;

import com.panic.core.redis.FailOpenRedisOps;
import com.panic.core.redis.StringRedisOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the optional Redis accelerator: the Upstash REST adapter wins when
 * {@code app.redis.upstash-rest-url}/{@code app.redis.upstash-rest-token} (fed from
 * UPSTASH_REDIS_REST_* in .env) are present, the TCP adapter against {@code app.redis.url}
 * (REDIS_URL) runs only when the REST credentials are missing, and with neither the workspace
 * simply runs without cache/quota/lock. Whichever adapter is chosen arrives already wrapped in
 * the fail-open guard, so an outage never breaks a chat.
 */
@Configuration(proxyBeanMethods = false)
public class RedisOpsConfig {

	private static final Logger log = LoggerFactory.getLogger(RedisOpsConfig.class);

	/** May return null (no Redis configured) — consumers take the bean as optional. */
	@Bean(destroyMethod = "close")
	public StringRedisOps stringRedisOps(Environment environment) {
		String restUrl = clean(environment.getProperty("app.redis.upstash-rest-url"));
		String restToken = clean(environment.getProperty("app.redis.upstash-rest-token"));
		String redisUrl = clean(environment.getProperty("app.redis.url"));
		StringRedisOps adapter = null;
		if (!restUrl.isBlank() && !restToken.isBlank()) {
			log.info("Redis accelerator: Upstash REST ({})", restUrl.replaceAll("https?://", ""));
			adapter = new UpstashRestRedisOps(restUrl, restToken);
		} else if (!redisUrl.isBlank()) {
			log.info("Redis accelerator: TCP ({})", redisUrl.replaceAll("://[^@]*@", "://"));
			adapter = new TcpRedisOps(redisUrl);
		}
		return adapter == null ? null : new FailOpenRedisOps(adapter);
	}

	/** .env values may carry dotenv-style quotes; whitespace or quoted blanks mean "not set". */
	private static String clean(String value) {
		return value == null ? "" : value.strip().replaceAll("^\"|\"$", "");
	}
}

package com.panic.agents.digest.digest;

import com.panic.agents.digest.DigestProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin trigger for the deterministic morning run: resolves today's date in the digest zone and calls
 * the same domain service every other surface uses. Disabled by {@code app.digest.scheduling-enabled=false}.
 * <p>
 * Deliberately untested directly — its whole body is "call the service", which the service tests cover.
 */
@Component
@ConditionalOnProperty(name = "app.digest.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class DigestMorningRun {

	private static final Logger log = LoggerFactory.getLogger(DigestMorningRun.class);

	private final DigestService digests;
	private final DigestProperties properties;

	public DigestMorningRun(DigestService digests, DigestProperties properties) {
		this.digests = digests;
		this.properties = properties;
	}

	@Scheduled(cron = "${app.digest.cron:0 30 5 * * *}", zone = "${app.digest.zone:Asia/Ho_Chi_Minh}")
	public void run() {
		LocalDate today = LocalDate.now(ZoneId.of(properties.getZone()));
		log.info("Morning digest run for {}", today);
		digests.generate(today, false);
	}
}

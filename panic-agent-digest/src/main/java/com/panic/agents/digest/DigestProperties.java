package com.panic.agents.digest;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator knobs of the digest feature ({@code app.digest.*}): timing, timezone, item count, and
 * the gates for the scheduler and the manual endpoint. Defaults are a morning run in Vietnam time
 * with a short 5–10 item digest, so the product works without any configuration.
 */
@ConfigurationProperties(prefix = "app.digest")
public class DigestProperties {

	/** Emit and run the scheduled morning job. */
	private boolean schedulingEnabled = true;

	/** Cron of the morning run (Spring 6-field syntax; seconds first). */
	private String cron = "0 30 5 * * *";

	/** Timezone the digest date and the morning run are anchored to. */
	private String zone = "Asia/Ho_Chi_Minh";

	/** Upper bound on summarized items in one digest. */
	private int maxItems = 8;

	/** Whether the manual generation endpoint is reachable; off by default so the public cannot trigger it. */
	private boolean manualGenerationEnabled = false;

	/** Feed fetch timeout in milliseconds. */
	private long feedTimeoutMs = 10_000L;

	/** Public origin used for share links and the sitemap; override in production. */
	private String publicBaseUrl = "http://localhost:8080";

	public ZoneId zoneId() {
		return ZoneId.of(zone);
	}

	public boolean isSchedulingEnabled() {
		return schedulingEnabled;
	}

	public void setSchedulingEnabled(boolean schedulingEnabled) {
		this.schedulingEnabled = schedulingEnabled;
	}

	public String getCron() {
		return cron;
	}

	public void setCron(String cron) {
		this.cron = cron;
	}

	public String getZone() {
		return zone;
	}

	public void setZone(String zone) {
		this.zone = zone;
	}

	public int getMaxItems() {
		return maxItems;
	}

	public void setMaxItems(int maxItems) {
		this.maxItems = maxItems;
	}

	public boolean isManualGenerationEnabled() {
		return manualGenerationEnabled;
	}

	public void setManualGenerationEnabled(boolean manualGenerationEnabled) {
		this.manualGenerationEnabled = manualGenerationEnabled;
	}

	public long getFeedTimeoutMs() {
		return feedTimeoutMs;
	}

	public void setFeedTimeoutMs(long feedTimeoutMs) {
		this.feedTimeoutMs = feedTimeoutMs;
	}

	public String getPublicBaseUrl() {
		return publicBaseUrl;
	}

	public void setPublicBaseUrl(String publicBaseUrl) {
		this.publicBaseUrl = publicBaseUrl;
	}
}

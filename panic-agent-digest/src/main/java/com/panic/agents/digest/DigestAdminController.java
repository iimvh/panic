package com.panic.agents.digest;

import com.panic.agents.digest.digest.Digest;
import com.panic.agents.digest.digest.DigestService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only manual generation. The controller only exists when
 * {@code app.digest.manual-generation-enabled=true}, so when the gate is off the route is not
 * reachable at all — bot traffic cannot trigger LLM spend.
 */
@RestController
@RequestMapping("/api/digest")
@ConditionalOnProperty(name = "app.digest.manual-generation-enabled", havingValue = "true")
public class DigestAdminController {

	private final DigestService digests;
	private final DigestProperties properties;

	public DigestAdminController(DigestService digests, DigestProperties properties) {
		this.digests = digests;
		this.properties = properties;
	}

	@PostMapping("/generate")
	public ResponseEntity<Map<String, Object>> generate(@RequestBody(required = false) GenerateRequest request) {
		LocalDate date = request == null || request.date() == null
				? LocalDate.now(ZoneId.of(properties.getZone()))
				: request.date();
		boolean force = request != null && request.force();
		Digest digest = digests.generate(date, force);
		return ResponseEntity.ok(Map.of(
				"date", digest.getDigestDate().toString(),
				"status", digest.getStatus().name(),
				"itemCount", digest.getItemCount()));
	}

	public record GenerateRequest(LocalDate date, boolean force) {
	}
}

package com.panic.agents.digest.agent;

import com.panic.agents.digest.DigestProperties;
import com.panic.agents.digest.digest.DigestService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Chat tools of the {@code digest} agent. Thin adapters over {@link DigestService}: they read the
 * stored digest (never re-summarize) and expose on-demand generation to the operator.
 */
public class DigestTools {

	private final DigestService digests;
	private final DigestProperties properties;

	public DigestTools(DigestService digests, DigestProperties properties) {
		this.digests = digests;
		this.properties = properties;
	}

	@Tool(description = "Answer with today's stored Vietnamese morning digest. Never summarizes again; "
			+ "says plainly when no digest exists yet.")
	public String today() {
		return digests.answerFor(LocalDate.now(ZoneId.of(properties.getZone())));
	}

	@Tool(description = "Answer with the stored digest for a specific date (ISO yyyy-MM-dd). "
			+ "Use for questions about a past day such as 'bản tin hôm qua có gì?'.")
	public String forDate(@ToolParam(description = "Digest date in ISO form, e.g. 2026-11-06") String date) {
		LocalDate parsed = parse(date);
		return digests.answerFor(parsed);
	}

	@Tool(description = "List the dates that have a stored digest, newest first, so the user can pick a day.")
	public String archive() {
		var dates = digests.availableDates();
		if (dates.isEmpty()) {
			return "Chưa có bản tin nào trong kho.";
		}
		return dates.stream().limit(14).map(LocalDate::toString).reduce((a, b) -> a + "\n" + b).orElse("");
	}

	@Tool(description = "Fetch the Vietnamese news feeds right now and return the latest headlines "
			+ "(title, source, link), newest first. Use this when the user wants the freshest news "
			+ "immediately or when no digest exists for today yet. Fast and free: it never calls the model.")
	public String refresh(@ToolParam(description = "Maximum number of headlines to return") int limit) {
		var items = digests.latestHeadlines(limit);
		if (items.isEmpty()) {
			return "Hiện chưa lấy được tin mới từ các nguồn RSS. Vui lòng thử lại sau ít phút.";
		}
		StringBuilder out = new StringBuilder("Tin mới nhất (").append(items.size()).append("):\n");
		for (var item : items) {
			out.append("- ").append(item.getTitle()).append(" (").append(item.getSource().getName()).append("): ")
					.append(item.getLink()).append('\n');
		}
		return out.toString().trim();
	}

	@Tool(description = "Generate the digest for a date on demand. Set force=true to regenerate an existing day. "
			+ "Only available when operators enable manual generation.")
	public String generate(@ToolParam(description = "Digest date in ISO form, e.g. 2026-11-06") String date,
			@ToolParam(description = "true to regenerate an existing digest") boolean force) {
		if (!properties.isManualGenerationEnabled()) {
			return "Tạo bản tin thủ công đang tắt. Bản tin được tạo tự động vào mỗi buổi sáng.";
		}
		LocalDate parsed = parse(date);
		var digest = digests.generate(parsed, force);
		return "Đã tạo bản tin ngày " + digest.getDigestDate() + ": " + digest.getStatus()
				+ " (" + digest.getItemCount() + " tin).";
	}

	private static LocalDate parse(String date) {
		try {
			return LocalDate.parse(date.trim());
		} catch (RuntimeException ex) {
			throw new IllegalArgumentException("Invalid date: " + date + " (expected yyyy-MM-dd)");
		}
	}
}

package com.panic.agents.digest.agent;

import com.panic.agents.digest.digest.DigestService;
import java.time.LocalDate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Chat tools of the {@code digestWriter} agent. Summarizing and saving are two steps of the same
 * daily generation: {@code summarize} ensures the items exist, {@code save} persists the digest.
 * Both are thin adapters over the domain service, where the actual writing happens once per day.
 */
public class DigestWriterTools {

	private final DigestService digests;

	public DigestWriterTools(DigestService digests) {
		this.digests = digests;
	}

	@Tool(description = "Ingest feeds and summarize the day's items into a digest body for a date "
			+ "(ISO yyyy-MM-dd). Uses the model once; returns a short status.")
	public String summarize(@ToolParam(description = "Digest date in ISO form, e.g. 2026-11-06") String date) {
		LocalDate parsed = LocalDate.parse(date.trim());
		var digest = digests.generate(parsed, false);
		return "Đã tóm tắt ngày " + digest.getDigestDate() + ": " + digest.getItemCount() + " tin.";
	}

	@Tool(description = "Verify and persist the digest for a date, regenerating when forced. "
			+ "Returns the stored status and item count.")
	public String save(@ToolParam(description = "Digest date in ISO form, e.g. 2026-11-06") String date,
			@ToolParam(description = "true to overwrite an existing digest") boolean force) {
		LocalDate parsed = LocalDate.parse(date.trim());
		var digest = digests.generate(parsed, force);
		return "Đã lưu bản tin ngày " + digest.getDigestDate() + ": " + digest.getStatus()
				+ " (" + digest.getItemCount() + " tin).";
	}
}

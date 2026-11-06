package com.panic.agents.digest.web;

import com.panic.agents.digest.DigestProperties;
import com.panic.agents.digest.digest.Digest;
import com.panic.agents.digest.digest.DigestSections;
import com.panic.agents.digest.digest.DigestService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Server-rendered public digest pages, built for SEO: descriptive titles, meta descriptions, JSON-LD,
 * visible source attribution, and cache headers so repeated reads never hit the database or the model.
 * The React SPA stays the chat surface; only these pages are server-rendered.
 * <p>
 * A slightly delayed generation never breaks the page: if today has no digest yet, the latest stored
 * day is served with a notice instead of an empty page.
 */
@Controller
@ConditionalOnProperty(name = "app.agents.digest.enabled", havingValue = "true", matchIfMissing = true)
public class DigestPageController {

	/** Public digest pages change at most once per morning; a short cache absorbs traffic spikes. */
	private static final CacheControl PAGE_CACHE = CacheControl.maxAge(java.time.Duration.ofMinutes(10))
			.cachePublic();

	private static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");

	private final DigestService digests;
	private final DigestProperties properties;
	private final TemplateRenderer renderer;
	private final DigestJsonLdWriter jsonLd;

	public DigestPageController(DigestService digests, DigestProperties properties, TemplateRenderer renderer,
			DigestJsonLdWriter jsonLd) {
		this.digests = digests;
		this.properties = properties;
		this.renderer = renderer;
		this.jsonLd = jsonLd;
	}

	@GetMapping("/ban-tin")
	public ResponseEntity<String> today() {
		Optional<Digest> today = digests.today();
		if (today.isPresent()) {
			return page(today.get(), false);
		}
		Optional<Digest> latest = digests.availableDates().stream().findFirst().flatMap(digests::find);
		if (latest.isPresent()) {
			return page(latest.get(), true);
		}
		Map<String, Object> variables = Map.of(
				"title", "Bản tin mỗi sáng",
				"description", "Bản tin mỗi sáng đang được tổng hợp. Vui lòng quay lại sau ít phút.");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.cacheControl(CacheControl.noStore())
				.contentType(HTML)
				.body(renderer.render("digest-unavailable", variables));
	}

	@GetMapping("/ban-tin/luu-tru")
	public ResponseEntity<String> archive() {
		List<Digest> available = digests.availableDates().stream()
				.map(digests::find)
				.flatMap(Optional::stream)
				.toList();
		Map<String, Object> variables = Map.of(
				"title", "Lưu trữ bản tin mỗi sáng",
				"description", "Danh sách các bản tin mỗi sáng trước đây để bạn xem lại những ngày đã bỏ lỡ.",
				"digests", available);
		return ResponseEntity.ok().cacheControl(PAGE_CACHE)
				.contentType(HTML).body(renderer.render("digest-archive", variables));
	}

	@GetMapping("/ban-tin/{date}")
	public ResponseEntity<String> datePage(@PathVariable String date) {
		LocalDate parsed;
		try {
			parsed = LocalDate.parse(date);
		} catch (RuntimeException ex) {
			return notFound();
		}
		return digests.find(parsed).map(digest -> page(digest, false)).orElseGet(this::notFound);
	}

	@GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
	@ResponseBody
	public ResponseEntity<String> sitemap() {
		String base = properties.getPublicBaseUrl();
		StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
				.append("  <url><loc>").append(base).append("/ban-tin</loc></url>\n")
				.append("  <url><loc>").append(base).append("/ban-tin/luu-tru</loc></url>\n");
		for (LocalDate date : digests.availableDates()) {
			xml.append("  <url><loc>").append(base).append("/ban-tin/").append(date).append("</loc></url>\n");
		}
		xml.append("</urlset>\n");
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml.toString());
	}

	@GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
	@ResponseBody
	public ResponseEntity<String> robots() {
		return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
				.body("User-agent: *\nAllow: /ban-tin\nSitemap: " + properties.getPublicBaseUrl()
						+ "/sitemap.xml\n");
	}

	private ResponseEntity<String> page(Digest digest, boolean stale) {
		DigestSections body = DigestSections.fromJson(digest.getSectionsJson());
		String title = "Bản tin mỗi sáng ngày " + digest.getDigestDate();
		String description = body.intro() == null || body.intro().isBlank()
				? "Tóm tắt tin tức Việt Nam trong ngày, chọn lọc theo chủ đề, kèm nguồn trích dẫn."
				: body.intro();
		List<String> related = digests.availableDates().stream()
				.filter(value -> !value.equals(digest.getDigestDate()))
				.limit(7).map(LocalDate::toString).toList();
		Map<String, Object> variables = Map.of(
				"title", title,
				"description", description,
				"digest", digest,
				"intro", body.intro(),
				"sections", body.sections(),
				"date", digest.getDigestDate().toString(),
				"stale", stale,
				"shareUrl", properties.getPublicBaseUrl() + "/ban-tin/" + digest.getDigestDate(),
				"jsonLd", jsonLd.write(digest, body),
				"relatedDates", related);
		return ResponseEntity.ok().cacheControl(PAGE_CACHE)
				.contentType(HTML).body(renderer.render("digest-page", variables));
	}

	private ResponseEntity<String> notFound() {
		Map<String, Object> variables = Map.of(
				"title", "Không tìm thấy bản tin",
				"description", "Bản tin cho ngày này không tồn tại hoặc chưa được tạo.");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.cacheControl(CacheControl.noStore())
				.contentType(HTML).body(renderer.render("digest-not-found", variables));
	}
}

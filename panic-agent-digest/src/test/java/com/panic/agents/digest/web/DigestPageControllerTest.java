package com.panic.agents.digest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.panic.agents.digest.DigestProperties;
import com.panic.agents.digest.digest.Digest;
import com.panic.agents.digest.digest.DigestSections;
import com.panic.agents.digest.digest.DigestService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web contract seam: a stored digest renders for its date, an unknown date is handled predictably,
 * the archive lists dates, the sitemap enumerates them, and cache headers are set. Rendered against
 * a stubbed service so the assertions are about the HTTP contract, not the template internals.
 */
class DigestPageControllerTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

	private DigestService digests;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		digests = org.mockito.Mockito.mock(DigestService.class);
		DigestProperties properties = new DigestProperties();
		org.thymeleaf.spring6.SpringTemplateEngine engine = new org.thymeleaf.spring6.SpringTemplateEngine();
		org.thymeleaf.templateresolver.ClassLoaderTemplateResolver resolver =
				new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
		resolver.setPrefix("templates/");
		resolver.setSuffix(".html");
		resolver.setCharacterEncoding("UTF-8");
		engine.setTemplateResolver(resolver);
		mvc = MockMvcBuilders.standaloneSetup(new DigestPageController(digests, properties,
				new TemplateRenderer(engine), new DigestJsonLdWriter())).build();
	}

	@Test
	void todaysDigestRendersWithSourceAttributionAndStructuredData() throws Exception {
		when(digests.today()).thenReturn(Optional.of(digest(DAY)));
		when(digests.find(DAY)).thenReturn(Optional.of(digest(DAY)));

		mvc.perform(get("/ban-tin"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Bản tin mỗi sáng")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Tin công nghệ")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("VnExpress")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("https://vnexpress.example/1")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("application/ld+json")))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")));
	}

	@Test
	void aDatePageRendersTheStoredDigestForThatDate() throws Exception {
		when(digests.find(DAY)).thenReturn(Optional.of(digest(DAY)));

		mvc.perform(get("/ban-tin/" + DAY))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("2026-11-06")))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")));
	}

	@Test
	void anUnknownDateIsANotFoundPageWithASearchableTitle() throws Exception {
		when(digests.find(DAY)).thenReturn(Optional.empty());

		mvc.perform(get("/ban-tin/" + DAY))
				.andExpect(status().isNotFound())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Không tìm thấy")));
	}

	@Test
	void archiveListsAvailableDates() throws Exception {
		when(digests.availableDates()).thenReturn(List.of(DAY, DAY.minusDays(1)));
		when(digests.find(DAY)).thenReturn(Optional.of(digest(DAY)));
		when(digests.find(DAY.minusDays(1))).thenReturn(Optional.of(digest(DAY.minusDays(1))));

		mvc.perform(get("/ban-tin/luu-tru"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("2026-11-06")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("2026-11-05")));
	}

	@Test
	void sitemapEnumeratesDigestDates() throws Exception {
		when(digests.availableDates()).thenReturn(List.of(DAY));

		mvc.perform(get("/sitemap.xml"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/xml"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("/ban-tin/2026-11-06")));
	}

	@Test
	void robotsPointsAtTheSitemap() throws Exception {
		mvc.perform(get("/robots.txt"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("sitemap.xml")));
	}

	private static Digest digest(LocalDate date) {
		DigestSections sections = DigestSections.groupByCategory(List.of(
				new DigestSections.Item("Tin công nghệ", "Tóm tắt tin công nghệ.",
						"https://vnexpress.example/1", "VnExpress", "technology")));
		Digest digest = new Digest();
		digest.setDigestDate(date);
		digest.setIntroText("Chào buổi sáng, đây là bản tin hôm nay.");
		digest.setSectionsJson(new DigestSections("Chào buổi sáng, đây là bản tin hôm nay.",
				sections.sections()).toJson());
		digest.setItemCount(sections.itemCount());
		digest.setStatus(Digest.Status.READY);
		digest.setCreatedAt(Instant.parse("2026-11-06T00:30:00Z"));
		return digest;
	}
}

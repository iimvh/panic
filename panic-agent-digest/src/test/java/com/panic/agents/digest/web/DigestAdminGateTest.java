package com.panic.agents.digest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.panic.agents.digest.DigestAdminController;
import com.panic.agents.digest.DigestProperties;
import com.panic.agents.digest.digest.Digest;
import com.panic.agents.digest.digest.DigestService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The manual generation endpoint is unreachable when its gate is off and reachable when on. This is
 * the operator's protection against bot traffic exhausting the LLM budget.
 */
class DigestAdminGateTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

	@Test
	void endpointIsNotRegisteredWhenTheGateIsOff() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(DigestService.class, () -> Mockito.mock(DigestService.class));
			context.registerBean(DigestProperties.class, DigestProperties::new);
			context.register(DigestAdminController.class);
			context.refresh();

			assertThat(context.getBeansOfType(DigestAdminController.class)).isEmpty();
		}
	}

	@Test
	void endpointGeneratesWhenTheGateIsOn() throws Exception {
		DigestService digests = Mockito.mock(DigestService.class);
		Digest digest = new Digest();
		digest.setDigestDate(DAY);
		digest.setStatus(Digest.Status.READY);
		digest.setItemCount(5);
		Mockito.when(digests.generate(DAY, true)).thenReturn(digest);

		DigestProperties properties = new DigestProperties();
		properties.setManualGenerationEnabled(true);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new DigestAdminController(digests, properties)).build();

		mvc.perform(post("/api/digest/generate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"date\":\"2026-11-06\",\"force\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.itemCount").value(5));
	}
}

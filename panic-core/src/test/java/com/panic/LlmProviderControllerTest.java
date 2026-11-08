package com.panic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panic.core.agent.chat.provider.LlmProviderRepository;
import com.panic.core.agent.chat.provider.LlmProviderStore;
import com.panic.core.agent.chat.provider.OpenRouterFreeModelCatalog;
import com.panic.core.agent.chat.provider.OpenRouterKeyTester;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * HTTP boundary of {@code /api/provider}: the status contract while disconnected, the double
 * connect round trip (a rejected key never lands in the database), the free-model listing and
 * the dry-run key probe. Provider fakes keep the suite off the real OpenRouter service.
 */
@SpringBootTest(classes = LlmProviderControllerTest.TestBoot.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:provider-controller;DB_CLOSE_DELAY=-1",
				"spring.jpa.hibernate.ddl-auto=create-drop",
				"spring.jpa.show-sql=false",
				"spring.ai.openai.api-key=missing-openrouter-api-key",
				"spring.ai.mcp.client.enabled=false" })
@AutoConfigureMockMvc
class LlmProviderControllerTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private LlmProviderStore store;

	@Autowired
	private LlmProviderRepository providers;

	@BeforeEach
	void disconnectEverythingLeftByEarlierTests() {
		providers.deleteAll();
	}

	@Test
	void disconnectedByDefaultAnswersNullProvider() throws Exception {
		mvc.perform(get("/api/provider")).andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").doesNotExist())
				.andExpect(jsonPath("$.model").doesNotExist())
				.andExpect(jsonPath("$.connected").value(false));

		assertThat(store.connected()).isFalse();
	}

	@Test
	void composerListsOnlyTheFreeModels() throws Exception {
		String body = mvc.perform(get("/api/provider/models")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode rows = new ObjectMapper().readTree(body);

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).path("id").asText()).isEqualTo("meta-llama/llama-3.3-70b-instruct:free");
		assertThat(rows.get(1).path("id").asText()).isEqualTo("openai/gpt-4o-mini:free");
	}

	@Test
	void connectVerifiesStoresAndAnswersTheStatus() throws Exception {
		mvc.perform(put("/api/provider")
						.contentType("application/json")
						.content("""
								{"provider":"openrouter","apiKey":"sk-or-v1-test-key-1234",
								 "model":"openai/gpt-4o-mini:free"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").value("openrouter"))
				.andExpect(jsonPath("$.model").value("openai/gpt-4o-mini:free"))
				.andExpect(jsonPath("$.connected").value(true))
				.andExpect(jsonPath("$.keyHint").isString());

		assertThat(store.connected()).isTrue();
		assertThat(store.current().orElseThrow().apiKey()).isEqualTo("sk-or-v1-test-key-1234");
	}

	@Test
	void rejectedKeyNeverReachesTheDatabase() throws Exception {
		mvc.perform(put("/api/provider")
						.contentType("application/json")
						.content("""
								{"provider":"openrouter","apiKey":"sk-or-v1-test-key-1234",
								 "model":"openai/gpt-4o-mini:free"}"""))
				.andExpect(status().isOk());

		mvc.perform(put("/api/provider")
						.contentType("application/json")
						.content("""
								{"provider":"openrouter","apiKey":"sk-or-v1-BAD-key",
								 "model":"meta-llama/llama-3.3-70b-instruct:free"}"""))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error").isNotEmpty());

		assertThat(store.connected()).isTrue();
		assertThat(store.current().orElseThrow().model()).isEqualTo("openai/gpt-4o-mini:free");
	}

	@Test
	void modelSwitchKeepsTheStoredKeyWithoutReEntry() throws Exception {
		mvc.perform(put("/api/provider")
						.contentType("application/json")
						.content("""
								{"provider":"openrouter","apiKey":"sk-or-v1-test-key-1234",
								 "model":"openai/gpt-4o-mini:free"}"""))
				.andExpect(status().isOk());

		// Blank key => keep the stored credential, only the model moves.
		mvc.perform(put("/api/provider")
						.contentType("application/json")
						.content("""
								{"provider":"openrouter","apiKey":"","model":"meta-llama/llama-3.3-70b-instruct:free"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.model").value("meta-llama/llama-3.3-70b-instruct:free"))
				.andExpect(jsonPath("$.connected").value(true));

		assertThat(store.current().orElseThrow().apiKey()).isEqualTo("sk-or-v1-test-key-1234");
	}

	@Test
	void blankKeyOnADisconnectedProviderIsRefused() throws Exception {
		mvc.perform(put("/api/provider")
						.contentType("application/json")
						.content("""
								{"provider":"openrouter","apiKey":"","model":"openai/gpt-4o-mini:free"}"""))
				.andExpect(status().isUnprocessableEntity());

		assertThat(store.connected()).isFalse();
	}

	@Test
	void reconnectSwapsTheModelForEveryNextRequest() throws Exception {
		mvc.perform(put("/api/provider")
						.contentType("application/json")
						.content("""
								{"provider":"openrouter","apiKey":"sk-or-v1-test-key-1234",
								 "model":"meta-llama/llama-3.3-70b-instruct:free"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.model").value("meta-llama/llama-3.3-70b-instruct:free"));
	}

	@Test
	void keyProbeDryRunsCandidateAndStoredKeys() throws Exception {
		mvc.perform(post("/api/provider/test")
						.contentType("application/json")
						.content("""
								{"apiKey":"sk-or-v1-candidate"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true))
				.andExpect(jsonPath("$.detail").isNotEmpty());

		mvc.perform(put("/api/provider")
						.contentType("application/json")
						.content("""
								{"provider":"openrouter","apiKey":"sk-or-v1-stored-9999",
								 "model":"openai/gpt-4o-mini:free"}"""))
				.andExpect(status().isOk());

		// Blank probe => the stored key of the active connection.
		mvc.perform(post("/api/provider/test")
						.contentType("application/json")
						.content("""
								{"apiKey":""}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true));
	}

	/** Fake OpenRouter: the model catalog and the key verdict come from fakes, never the network. */
	static class FixedFreeModelCatalog extends OpenRouterFreeModelCatalog {

		FixedFreeModelCatalog() {
			super(RestClient.builder());
		}

		@Override
		public List<FreeModel> freeModels() {
			return List.of(
					new FreeModel("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B Instruct (free)", 131072),
					new FreeModel("openai/gpt-4o-mini:free", "GPT-4o mini (free)", 128000));
		}
	}

	static class AcceptingKeyTester extends OpenRouterKeyTester {

		AcceptingKeyTester() {
			super(RestClient.builder());
		}

		@Override
		public TestOutcome check(String apiKey) {
			boolean rejected = apiKey != null && apiKey.contains("BAD");
			return new TestOutcome(!rejected, rejected ? "Key rejected by OpenRouter" : "Key connected ('test')");
		}
	}

	@SpringBootConfiguration
	@AutoConfigurationPackage(basePackages = "com.panic")
	@EnableAutoConfiguration
	@Import({ com.panic.controller.LlmProviderController.class, LlmProviderStore.class,
			com.panic.exception.GlobalExceptionHandler.class })
	static class TestBoot {

		@Bean
		FixedFreeModelCatalog modelCatalog() {
			return new FixedFreeModelCatalog();
		}

		@Bean
		AcceptingKeyTester keyTester() {
			return new AcceptingKeyTester();
		}
	}
}

package panic.llm;

import panic.util.CancelToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Root;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProvidersTest {

  @Test
  void openRouterModelsMarkFreeOnesAndKeyCheckReadsLabel() throws Exception {
    try (FakeServer server = new FakeServer(ex -> {
      String path = ex.getRequestURI().getPath();
      if (path.endsWith("/models")) {
        FakeServer.json(ex, 200, """
            {"data":[
              {"id":"b/paid","name":"Paid","context_length":8000,"pricing":{"prompt":"0.000001","completion":"0.000002"}},
              {"id":"a/zero","name":"Zero","pricing":{"prompt":"0","completion":"0"}},
              {"id":"c/x:free","name":"Free"}]}""");
      } else if (path.endsWith("/key")) {
        if ("Bearer good".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
          FakeServer.json(ex, 200, "{\"data\":{\"label\":\"dev\",\"is_free_tier\":true}}");
        } else {
          FakeServer.json(ex, 401, "{\"error\":{\"message\":\"bad key\"}}");
        }
      }
    })) {
      Root root = Context.root("t");
      try {
        ConfigHandle<ProviderConfig> handle = root.plugin("openrouter", OpenRouter.plugin(), ProviderConfig.NONE);
        assertFalse(root.isProvided(Providers.key(OpenRouter.ID)), "no key, no provider");

        handle.update(new ProviderConfig("good", server.url()));
        Provider[] p = new Provider[1];
        root.plugin("probe", context.Plugin.require(Providers.key(OpenRouter.ID)).then(ctx -> {
          p[0] = ctx.get(Providers.key(OpenRouter.ID));
          return null;
        }));
        List<ModelInfo> models = p[0].models(new CancelToken());
        assertEquals(List.of("a/zero", "b/paid", "c/x:free"), models.stream().map(ModelInfo::id).toList());
        assertEquals(List.of(true, false, true), models.stream().map(ModelInfo::free).toList());
        assertEquals(8000, models.get(1).contextLength());

        KeyCheck ok = p[0].checkKey(new CancelToken());
        assertTrue(ok.ok());
        assertTrue(ok.detail().contains("dev"), ok.detail());

        handle.update(new ProviderConfig("bad", server.url()));
        assertTrue(root.isProvided(Providers.key(OpenRouter.ID)), "a wrong key still connects; checkKey tells");
        KeyCheck bad = p[0].checkKey(new CancelToken());
        assertFalse(bad.ok());
        assertEquals("openrouter rejected the key", bad.detail());

        handle.update(ProviderConfig.NONE);
        assertFalse(root.isProvided(Providers.key(OpenRouter.ID)), "key removed: provider retracted");
      } finally {
        root.dispose();
      }
    }
  }

  @Test
  void customProviderWithoutKeyConnectsAndSendsNoAuthorization() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 200,
        "{\"data\":[{\"id\":\"qwen3:8b\"},{\"id\":\"llama3\"}]}"))) {
      Root root = Context.root("t");
      try {
        root.plugin(CustomProvider.PLUGIN, CustomProvider.plugin(), new CustomProvidersConfig(
            Map.of("ollama", new CustomProvidersConfig.Endpoint(server.url(), null))));
        assertTrue(root.isProvided(Providers.key("ollama")), "no apiKey = no key needed, so connected");

        Provider provider = provider(root, "ollama");
        List<ModelInfo> models = provider.models(new CancelToken());
        assertEquals(List.of("llama3", "qwen3:8b"), models.stream().map(ModelInfo::id).toList());
        assertTrue(models.stream().allMatch(m -> m.free() == null), "no model is known to be free");

        KeyCheck check = provider.checkKey(new CancelToken());
        assertTrue(check.ok());
        assertTrue(check.detail().contains("2 models"), check.detail());
        assertNull(server.lastAuth, "no Authorization header without a key");

        assertTrue(root.collect(Providers.STATUS, Providers.Query.ALL)
            .contains(new Providers.Status("ollama", true)));
      } finally {
        root.dispose();
      }
    }
  }

  @Test
  void customProviderKeyStates() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 401, "{\"message\":\"Unauthorized\"}"))) {
      Root root = Context.root("t");
      try {
        ConfigHandle<CustomProvidersConfig> handle = root.plugin(CustomProvider.PLUGIN, CustomProvider.plugin(),
            CustomProvidersConfig.DEFAULT);
        assertFalse(root.isProvided(Providers.key("groq")), "no entry, no provider");

        handle.update(entry("groq", new CustomProvidersConfig.Endpoint(server.url(), "")));
        assertFalse(root.isProvided(Providers.key("groq")), "blank key = missing, not connected");
        assertTrue(root.collect(Providers.STATUS, Providers.Query.ALL)
            .contains(new Providers.Status("groq", false)));

        handle.update(entry("groq", new CustomProvidersConfig.Endpoint(server.url(), "k")));
        assertTrue(root.isProvided(Providers.key("groq")));
        KeyCheck bad = provider(root, "groq").checkKey(new CancelToken());
        assertFalse(bad.ok());
        assertEquals("groq rejected the key", bad.detail());
        assertEquals("Bearer k", server.lastAuth);

        handle.update(CustomProvidersConfig.DEFAULT);
        assertFalse(root.isProvided(Providers.key("groq")), "entry removed: provider retracted");

        handle.update(entry("groq", new CustomProvidersConfig.Endpoint(server.url(), null, null, false)));
        assertFalse(root.isProvided(Providers.key("groq")), "disabled: listed, not connected");
        assertTrue(root.collect(Providers.STATUS, Providers.Query.ALL)
            .contains(new Providers.Status("groq", false)));
      } finally {
        root.dispose();
      }
    }
  }

  @Test
  void editingOneCustomProviderLeavesTheOthersRunning() {
    Root root = Context.root("t");
    try {
      CustomProvidersConfig.Endpoint ollama = new CustomProvidersConfig.Endpoint("http://127.0.0.1:1/v1", null);
      ConfigHandle<CustomProvidersConfig> handle = root.plugin(CustomProvider.PLUGIN, CustomProvider.plugin(),
          new CustomProvidersConfig(Map.of("ollama", ollama,
              "mistral", new CustomProvidersConfig.Endpoint("http://127.0.0.1:2/v1", "a"))));
      java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
      // stands for llm's active:ollama branch, and so for chat and every running turn on it
      root.plugin("uses-ollama", context.Plugin.require(Providers.key("ollama")).then(ctx -> {
        starts.incrementAndGet();
        return null;
      }));
      assertEquals(1, starts.get());

      handle.update(new CustomProvidersConfig(Map.of("ollama", ollama,
          "mistral", new CustomProvidersConfig.Endpoint("http://127.0.0.1:2/v1", "b"),
          "groq", new CustomProvidersConfig.Endpoint("http://127.0.0.1:3/v1", "c"))));
      assertEquals(1, starts.get(), "ollama was not retracted while mistral changed and groq came");
      assertTrue(root.isProvided(Providers.key("groq")));

      handle.update(new CustomProvidersConfig(Map.of("ollama", ollama)));
      assertEquals(1, starts.get());
      assertFalse(root.isProvided(Providers.key("mistral")), "a removed entry is retracted");
      assertFalse(root.collect(Providers.STATUS, Providers.Query.ALL).stream().anyMatch(st -> st.id().equals("groq")));

      handle.update(new CustomProvidersConfig(Map.of("ollama",
          new CustomProvidersConfig.Endpoint("http://127.0.0.1:9/v1", null))));
      assertEquals(2, starts.get(), "its own change restarts its dependents");
    } finally {
      root.dispose();
    }
  }

  @Test
  void customProvidersConfigRejectsBadEntries() {
    assertThrows(IllegalArgumentException.class, () -> new CustomProvidersConfig.Endpoint(" ", null));
    assertThrows(IllegalArgumentException.class, () -> new CustomProvidersConfig(
        Map.of("bad id", new CustomProvidersConfig.Endpoint("http://x/v1", null))));
    assertThrows(IllegalArgumentException.class, () -> new CustomProvidersConfig(
        Map.of(OpenRouter.ID, new CustomProvidersConfig.Endpoint("http://x/v1", null))), "a built-in id");
    assertFalse(new CustomProvidersConfig.Endpoint("http://x/v1", "secret").toString().contains("secret"));
  }

  private static CustomProvidersConfig entry(String id, CustomProvidersConfig.Endpoint endpoint) {
    return new CustomProvidersConfig(Map.of(id, endpoint));
  }

  /** Installs a probe that captures the provider {@code id} once it is connected. */
  private static Provider provider(Root root, String id) {
    Provider[] p = new Provider[1];
    root.plugin("probe-" + id, context.Plugin.require(Providers.key(id)).then(ctx -> {
      p[0] = ctx.get(Providers.key(id));
      return null;
    }));
    return p[0];
  }
}

package panic.llm;

import panic.util.CancelToken;
import panic.util.Json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Root;
import java.util.List;
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
  void rejectedKeyIsReportedNotThrown() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 401, "{\"message\":\"Unauthorized\"}"))) {
      Mistral mistral = new Mistral(new OpenAiCompatClient(Mistral.ID, server.url(), "bad"), new CancelToken());
      KeyCheck check = mistral.checkKey(new CancelToken());
      assertFalse(check.ok());
      assertEquals("mistral rejected the key", check.detail());
      assertNull(mistral.free("x", Json.object()));
    }
  }
}

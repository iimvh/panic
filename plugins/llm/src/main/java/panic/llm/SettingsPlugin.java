package panic.llm;

import context.Context;
import context.Plugin;
import context.ServiceKey;
import core.Disposable;
import java.util.List;
import panic.command.Commands;
import panic.plugin.Configs;

/**
 * Commands {@code provider [id]} and {@code model [id]}: they change the {@code llm} plugin's config at
 * runtime. A plugin of its own, because a plugin must not restart itself from one of its own commands.
 */
public final class SettingsPlugin implements Plugin {

  @Override
  public List<ServiceKey<?>> requires() {
    return List.of(Configs.KEY);
  }

  @Override
  public Disposable apply(Context ctx) {
    Configs configs = ctx.get(Configs.KEY);
    Commands.command(ctx, Llm.PROVIDER_COMMAND + " [id]", "show or switch the provider", call -> {
      List<String> ids = ctx.collect(Providers.STATUS, Providers.Query.ALL).stream()
          .map(Providers.Status::id).sorted().toList();
      LlmConfig cfg = configs.get("llm", LlmConfig.class);
      String id = call.arg("id");
      if (id == null) {
        call.out().println("provider: " + cfg.provider() + "   available: " + ids);
      } else if (!ids.contains(id)) {
        call.out().println("unknown provider '" + id + "', one of " + ids);
      } else {
        configs.update("llm", cfg.withProvider(id));
        Commands.run(ctx, Commands.STATUS, "", call.session());
      }
    });
    Commands.command(ctx, Llm.MODEL_COMMAND + " [id]", "show or switch the model", call -> {
      LlmConfig cfg = configs.get("llm", LlmConfig.class);
      String id = call.arg("id");
      if (id == null) {
        call.out().println("model: " + cfg.model());
      } else {
        configs.update("llm", cfg.withModel(id));
        Commands.run(ctx, Commands.STATUS, "", call.session());
      }
    });
    return null;
  }
}

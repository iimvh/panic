package panic.tools;

import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import panic.command.Commands;
import panic.command.UsageException;

/**
 * The permission policy for tool calls: answers {@link Tools#POLICY} from {@link PermissionsConfig}. It
 * requires nothing and nothing requires it, so changing its section restarts only this plugin and the next
 * call follows the new rules. While it is stopped, {@link Tools.Verdict#fallback} applies (read allow, else
 * ask), so a gap never opens the gate.
 *
 * Bypass mode (YOLO): with {@link PermissionsConfig#bypassPermissions}, or switched on for one conversation with
 * command {@code bypass on}, a call the rules would ask about is allowed; a rule that denies still denies. The
 * per-conversation switches live in memory only (they are not config, so this plugin does not update its own),
 * and are forgotten when the plugin restarts, e.g. on a config change. {@link Tools#BYPASS} tells front-ends the
 * state.
 */
public final class PermissionsPlugin implements ConfigPlugin<PermissionsConfig> {

  @Override
  public Disposable apply(Context ctx, PermissionsConfig cfg) {
    // only conversations that differ from the config default are kept
    Map<String, Boolean> overrides = new ConcurrentHashMap<>();
    ctx.hook(Tools.POLICY, check -> {
      Permission rule = cfg.permission(check.call().name(), check.risk());
      if (rule == Permission.ASK && bypassed(cfg, overrides, check.conversationId())) rule = Permission.ALLOW;
      return new Tools.Verdict(rule, cfg.askTimeoutSeconds());
    });
    ctx.hook(Tools.BYPASS, q ->
        new Tools.Bypass(bypassed(cfg, overrides, q.conversationId()), cfg.bypassPermissions()));
    Commands.command(ctx, "bypass [state]",
        "run tools without asking in this chat (YOLO): on | off | status; deny rules still apply", call -> {
          String id = call.session().id();
          String state = call.argOr("state", "status");
          switch (state) {
            case "on" -> {
              set(cfg, overrides, id, true);
              call.out().println("bypass on: the agent can now write files, run commands and use the network "
                  + "in this chat without asking (tools denied in the config stay denied)");
            }
            case "off" -> {
              set(cfg, overrides, id, false);
              call.out().println("bypass off: calls that need approval ask again");
            }
            case "status" -> call.out().println("bypass " + (bypassed(cfg, overrides, id) ? "on" : "off")
                + " in this chat (config default: " + (cfg.bypassPermissions() ? "on" : "off") + ")");
            default -> throw new UsageException("unknown state '" + state + "'", "bypass [on|off|status]");
          }
        });
    return null;
  }

  private static boolean bypassed(PermissionsConfig cfg, Map<String, Boolean> overrides, String conversationId) {
    if (conversationId == null) return cfg.bypassPermissions();
    return overrides.getOrDefault(conversationId, cfg.bypassPermissions());
  }

  private static void set(PermissionsConfig cfg, Map<String, Boolean> overrides, String id, boolean on) {
    if (on == cfg.bypassPermissions()) overrides.remove(id);
    else overrides.put(id, on);
  }
}

package panic.tools;

import context.ConfigPlugin;
import context.Context;
import core.Disposable;

/**
 * The permission policy for tool calls: answers {@link Tools#POLICY} from {@link PermissionsConfig}. It
 * requires nothing and nothing requires it, so changing its section restarts only this plugin and the next
 * call follows the new rules. While it is stopped, {@link Tools.Verdict#fallback} applies (read allow, else
 * ask), so a gap never opens the gate.
 */
public final class PermissionsPlugin implements ConfigPlugin<PermissionsConfig> {

  @Override
  public Disposable apply(Context ctx, PermissionsConfig cfg) {
    ctx.hook(Tools.POLICY, check ->
        new Tools.Verdict(cfg.permission(check.call().name(), check.risk()), cfg.askTimeoutSeconds()));
    return null;
  }
}

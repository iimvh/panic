package panic.tools;

import context.Context;
import context.Plugin;
import core.Disposable;
import java.util.List;
import panic.command.Commands;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;

/**
 * Lets a user see and try the tools by hand. It owns no tool and needs no service: the tools come and go with
 * the plugins that register them.
 *
 * Commands: {@code tools} (list, with each tool's risk and what the policy says about it),
 * {@code tool <name> [args...]} (run one; args is JSON, default {@code {}}, in the session's conversation; it
 * passes the same permission gate as the model's calls).
 */
public final class ToolsPlugin implements Plugin {

  @Override
  public Disposable apply(Context ctx) {
    Commands.command(ctx, "tools", "list the tools the model can call, with their risk and permission", call -> {
      List<Tools.Info> infos = Tools.infos(ctx);
      if (infos.isEmpty()) call.out().println("  (none)");
      for (Tools.Info i : infos) {
        ToolSpec s = i.spec();
        Permission p = Tools.verdict(ctx, new ToolCall("list", s.name(), "{}"), i.risk()).permission();
        call.out().println("  " + s.name() + " [" + i.risk().wire() + ": " + p.wire() + "]"
            + (s.description().isBlank() ? "" : "  " + s.description()));
      }
    });
    Commands.command(ctx, "tool <name> [args...]", "run a tool by hand (args: JSON object)", call -> {
      String args = call.argOr("args", "{}");
      ToolCall toolCall = new ToolCall("manual", call.arg("name"), args);
      ToolResult result = Tools.call(ctx, toolCall, new ToolContext(call.session().id(), call.signal()));
      call.out().println((result.isError() ? "error: " : "") + result.content());
    });
    return null;
  }
}

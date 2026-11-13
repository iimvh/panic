package panic.app;

import context.Context;
import context.Plugin;
import core.CancelledException;
import core.Signal;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import panic.chat.Chat;
import panic.chat.Conversations;
import panic.llm.KeyCheck;
import panic.llm.Llm;
import panic.llm.LlmConfig;
import panic.llm.LlmException;
import panic.llm.ModelInfo;

/**
 * The terminal chat bot. It depends only on Conversations, never on the LLM: it asks {@link Chat#ASK} and
 * {@link Llm#CURRENT}, so switching provider or model never restarts the REPL itself.
 */
public final class Repl {

  static final String CONVERSATION = "repl";
  private static final int MODELS_SHOWN = 60;

  private final Panic panic;
  private final Stdin in;
  private final PrintStream out;
  private final Runnable exit;

  public Repl(Panic panic, Stdin in, PrintStream out, Runnable exit) {
    this.panic = panic;
    this.in = in;
    this.out = out;
    this.exit = exit;
  }

  public Plugin plugin() {
    return Plugin.require(Conversations.KEY).then(ctx -> {
      ctx.spawn("loop", signal -> {
        loop(ctx, signal);
        return null;
      });
      return null;
    });
  }

  private void loop(Context ctx, Signal signal) {
    out.println("panic — type a message, or /help");
    status(ctx);
    try {
      while (!signal.isCancelled()) {
        out.print("\n> ");
        out.flush();
        String line = in.readLine();
        if (line == null) break;
        line = line.strip();
        if (line.isEmpty()) continue;
        try {
          if (line.startsWith("/")) {
            if (!command(ctx, signal, line)) break;
          } else {
            ask(ctx, signal, line);
          }
        } catch (CancelledException e) {
          if (signal.isCancelled()) return;
          out.println("\n[cancelled]");
        } catch (LlmException e) {
          out.println("\n[error] " + e.getMessage());
        } catch (RuntimeException e) {
          out.println("\n[error] " + e);
        }
      }
    } catch (InterruptedException stopped) {
      return;
    }
    if (!signal.isCancelled()) exit.run();
  }

  private void ask(Context ctx, Signal signal, String text) {
    Optional<Chat.Reply> reply = Chat.ask(ctx, new Chat.Ask(CONVERSATION, text, signal, piece -> {
      out.print(piece);
      out.flush();
    }));
    if (reply.isEmpty()) {
      out.println("[no LLM connected] set a provider key and a model: /provider, /model, or panic.json");
      return;
    }
    Chat.Reply r = reply.get();
    out.println();
    StringBuilder meta = new StringBuilder("[").append(r.model());
    if (r.usage().totalTokens() > 0) meta.append(" · ").append(r.usage().totalTokens()).append(" tokens");
    if (r.finishReason() != null && !"stop".equals(r.finishReason())) meta.append(" · ").append(r.finishReason());
    out.println(meta.append("]"));
  }

  /** Returns false to leave the loop. */
  private boolean command(Context ctx, Signal signal, String line) {
    String[] parts = line.split("\\s+", 2);
    String arg = parts.length > 1 ? parts[1].strip() : "";
    switch (parts[0].toLowerCase(Locale.ROOT)) {
      case "/help" -> help();
      case "/exit", "/quit" -> {
        return false;
      }
      case "/status" -> status(ctx);
      case "/provider" -> {
        if (arg.isEmpty()) {
          out.println("provider: " + panic.llm().provider() + "   available: " + panic.providerIds());
        } else if (!panic.providerIds().contains(arg)) {
          out.println("unknown provider '" + arg + "', one of " + panic.providerIds());
        } else {
          panic.llm(panic.llm().withProvider(arg));
          status(ctx);
        }
      }
      case "/model" -> {
        if (arg.isEmpty()) {
          out.println("model: " + panic.llm().model());
        } else {
          panic.llm(panic.llm().withModel(arg));
          status(ctx);
        }
      }
      case "/models" -> models(ctx, signal, arg);
      case "/check" -> current(ctx).ifPresentOrElse(llm -> {
        KeyCheck check = llm.provider().checkKey(signal);
        out.println((check.ok() ? "ok: " : "failed: ") + check.detail());
      }, this::notConnected);
      case "/reset" -> {
        ctx.get(Conversations.KEY).get(CONVERSATION).clear();
        out.println("conversation cleared");
      }
      default -> out.println("unknown command " + parts[0] + ", try /help");
    }
    return true;
  }

  private void models(Context ctx, Signal signal, String filter) {
    Optional<Llm> llm = current(ctx);
    if (llm.isEmpty()) {
      notConnected();
      return;
    }
    boolean freeOnly = filter.equalsIgnoreCase("free");
    String needle = freeOnly ? "" : filter.toLowerCase(Locale.ROOT);
    List<ModelInfo> models = llm.get().provider().models(signal).stream()
        .filter(m -> !freeOnly || Boolean.TRUE.equals(m.free()))
        .filter(m -> needle.isEmpty() || m.id().toLowerCase(Locale.ROOT).contains(needle))
        .toList();
    for (ModelInfo m : models.subList(0, Math.min(models.size(), MODELS_SHOWN))) {
      String ctxLen = m.contextLength() > 0 ? "  " + (m.contextLength() / 1000) + "k" : "";
      String free = Boolean.TRUE.equals(m.free()) ? "  free" : "";
      out.println("  " + m.id() + ctxLen + free);
    }
    if (models.size() > MODELS_SHOWN) out.println("  ... " + (models.size() - MODELS_SHOWN) + " more, add a filter");
    if (models.isEmpty()) out.println("  (none)");
  }

  private void status(Context ctx) {
    LlmConfig cfg = panic.llm();
    String state = current(ctx).isPresent() ? "connected" : "not connected";
    out.println("provider: " + orNone(cfg.provider()) + "   model: " + orNone(cfg.model()) + "   (" + state + ")");
  }

  private Optional<Llm> current(Context ctx) {
    return ctx.bail(Llm.CURRENT, Llm.Query.CURRENT);
  }

  private void notConnected() {
    out.println("[no LLM connected] the provider has no key, or none is selected");
  }

  private void help() {
    out.println("""
        /provider [id]   show or switch provider (openrouter, mistral)
        /model [id]      show or switch model
        /models [free|text]  list the provider's models
        /check           check the provider's API key
        /status          show what is connected
        /reset           forget this conversation
        /exit            quit""");
  }

  private static String orNone(String s) {
    return s == null || s.isBlank() ? "<none>" : s;
  }
}

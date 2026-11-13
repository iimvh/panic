package panic.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A command declared from a spec, Koishi style: {@code "model [id]"}, {@code "ask <text...>"}.
 * {@code <name>} is required, {@code [name]} optional, {@code ...} (last parameter only) takes the rest of
 * the line as typed.
 */
public final class Command {

  /** One parameter of a spec. */
  public record Param(String name, boolean required, boolean rest) {
    @Override
    public String toString() {
      String inner = name + (rest ? "..." : "");
      return required ? "<" + inner + ">" : "[" + inner + "]";
    }
  }

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
  private static final Pattern PARAM = Pattern.compile("([<\\[])([a-zA-Z][a-zA-Z0-9-]*)(\\.\\.\\.)?([>\\]])");

  private final String name;
  private final List<Param> params;
  private final String description;
  private final Action action;

  private Command(String name, List<Param> params, String description, Action action) {
    this.name = name;
    this.params = List.copyOf(params);
    this.description = description;
    this.action = action;
  }

  public static Command parse(String spec, String description, Action action) {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(action, "action");
    String[] parts = spec.strip().split("\\s+");
    if (!NAME.matcher(parts[0]).matches()) {
      throw new IllegalArgumentException("bad command name '" + parts[0] + "' in spec '" + spec + "'");
    }
    List<Param> params = new ArrayList<>();
    for (int i = 1; i < parts.length; i++) {
      var m = PARAM.matcher(parts[i]);
      boolean angle = m.matches() && m.group(1).equals("<") && m.group(4).equals(">");
      boolean square = m.matches() && m.group(1).equals("[") && m.group(4).equals("]");
      if (!angle && !square) throw new IllegalArgumentException("bad parameter '" + parts[i] + "' in spec '" + spec + "'");
      Param p = new Param(m.group(2), angle, m.group(3) != null);
      if (!params.isEmpty() && params.get(params.size() - 1).rest()) {
        throw new IllegalArgumentException("only the last parameter may take the rest, in '" + spec + "'");
      }
      if (p.required() && params.stream().anyMatch(q -> !q.required())) {
        throw new IllegalArgumentException("a required parameter cannot follow an optional one, in '" + spec + "'");
      }
      if (params.stream().anyMatch(q -> q.name().equals(p.name()))) {
        throw new IllegalArgumentException("duplicate parameter '" + p.name() + "' in '" + spec + "'");
      }
      params.add(p);
    }
    return new Command(parts[0], params, description == null ? "" : description, action);
  }

  public String name() {
    return name;
  }

  public List<Param> params() {
    return params;
  }

  public String description() {
    return description;
  }

  /** e.g. {@code model [id]} */
  public String usage() {
    StringBuilder sb = new StringBuilder(name);
    for (Param p : params) sb.append(' ').append(p);
    return sb.toString();
  }

  /** Binds an argument line to the parameters. Throws UsageException when it does not fit. */
  public Map<String, String> bind(String argLine) {
    String line = argLine == null ? "" : argLine;
    List<Tokens.Token> tokens = Tokens.split(line);
    Map<String, String> args = new LinkedHashMap<>();
    int t = 0;
    for (Param p : params) {
      if (t >= tokens.size()) {
        if (p.required()) throw new UsageException("missing <" + p.name() + ">", usage());
        break;
      }
      if (p.rest()) {
        // one token: its unquoted value; several: the line exactly as typed
        String value = tokens.size() - t == 1 ? tokens.get(t).value() : line.substring(tokens.get(t).start()).strip();
        args.put(p.name(), value);
        t = tokens.size();
      } else {
        args.put(p.name(), tokens.get(t++).value());
      }
    }
    if (t < tokens.size()) throw new UsageException("too many arguments", usage());
    return args;
  }

  void run(String argLine, Session session) {
    Call call = new Call(this, bind(argLine), session);
    try {
      action.run(call);
    } catch (RuntimeException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new core.CancelledException("interrupted");
    } catch (Exception e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  @Override
  public String toString() {
    return "Command[" + usage() + "]";
  }
}

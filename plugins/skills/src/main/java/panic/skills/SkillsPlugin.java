package panic.skills;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import panic.chat.Chat;
import panic.command.Call;
import panic.command.Commands;
import panic.command.Prompts;
import panic.llm.ToolSpec;
import panic.skills.SkillStore.Skill;
import panic.skills.SkillStore.SkillException;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.tools.Workspace;
import panic.util.Json;

/**
 * Agent Skills, after Claude Code's skills: folders with a {@code SKILL.md} (instructions and front matter) and
 * the files it uses. Only each skill's name and description go in the system prompt (section {@code Skills}); the
 * model loads a skill with the tool {@code skill} when a task matches it, and the user runs one with
 * {@code /name args} (answered on {@link Prompts#EXPAND}, so the line goes through the normal ask). Command
 * {@code skills} lists them.
 *
 * Requires nothing and provides no service, so a config change restarts this plugin alone, after any call still
 * running. Skills are looked up on each use, so a new or edited {@code SKILL.md} counts from the next ask.
 */
public final class SkillsPlugin implements ConfigPlugin<SkillsConfig> {

  static final String SECTION = "Skills";
  /** After {@code Memory} (3), before {@code Plan mode} (5), which is switched per chat. */
  static final int ORDER = 4;
  static final String TOOL = "skill";

  static final String GUIDE = """
      Skills are instructions (and files) written for particular tasks. When the task matches a skill's \
      description, call `skill` with its name before you start, then follow it. A user line `/name ...` followed \
      by a `skill` result means the user ran that skill: it is already loaded, follow it.""";

  private final Consumer<String> warn;

  public SkillsPlugin(Consumer<String> warn) {
    this.warn = warn;
  }

  @Override
  public Disposable apply(Context ctx, SkillsConfig cfg) {
    if (!cfg.enabled()) return null;
    SkillStore store = new SkillStore(warn);
    Path fallback = Path.of(cfg.root());
    Skills skills = conversationId -> store.list(cfg, Workspace.root(ctx, conversationId, fallback));
    Tools.register(ctx, new Load(skills, cfg.maxSkillChars()));
    // a sub-agent gets no list (as in Claude Code): every one of its requests would pay for it, and its profile
    // may not offer the tool. It can still load a skill its task names.
    Chat.contribute(ctx, SECTION, ORDER, q -> q.delegated() ? null : section(skills.of(q.origin()),
        cfg.maxListChars()));
    ctx.hook(Prompts.EXPAND, q -> {
      Skill s = skills.of(q.conversationId()).get(q.name());
      if (s == null || !s.user()) return null;
      return new Prompts.Prompt(s.name(), s.description(), TOOL, arguments(s.name(), q.args()),
          SkillStore.render(s, q.args(), cfg.maxSkillChars()));
    });
    Commands.command(ctx, "skills [name]", "the skills the agent can load (and you can run as /name)",
        call -> list(ctx, skills, call));
    return null;
  }

  /** The skills of a conversation's project and the user's, by name. */
  @FunctionalInterface
  interface Skills {
    Map<String, Skill> of(String conversationId);
  }

  /** The section: the guide and one line per skill the model may load; null when there is none. */
  static String section(Map<String, Skill> all, int max) {
    StringBuilder list = new StringBuilder();
    int left = 0;
    for (Skill s : all.values()) {
      if (!s.model()) continue;
      String line = "- " + s.name() + ": " + s.description().replace('\n', ' ') + "\n";
      if (left > 0 || list.length() + line.length() > max) {
        left++;
        continue;
      }
      list.append(line);
    }
    if (list.isEmpty() && left == 0) return null;
    StringBuilder out = new StringBuilder(GUIDE).append("\n\n").append(list);
    if (left > 0) {
      out.append("[").append(left).append(left == 1 ? " more skill" : " more skills")
          .append(" not listed (skills.maxListChars); ask the user if a task needs one]\n");
    }
    return out.toString().stripTrailing();
  }

  private static String arguments(String name, String args) {
    var node = Json.object().put("name", name);
    if (args != null && !args.isBlank()) node.put("args", args);
    return node.toString();
  }

  private static void list(Context ctx, Skills skills, Call call) {
    Map<String, Skill> all = skills.of(call.session().id());
    String name = call.arg("name");
    if (name != null) {
      Skill s = all.get(name);
      if (s == null) {
        call.out().println("no skill " + name + (all.isEmpty() ? "" : "; skills: " + String.join(", ", all.keySet())));
        return;
      }
      call.out().println(s.name() + "  " + s.dir().resolve(SkillStore.FILE));
      call.out().println(s.description());
      call.out().println(flags(ctx, s));
      for (String f : SkillStore.files(s.dir())) call.out().println("  " + f);
      return;
    }
    if (all.isEmpty()) {
      call.out().println("no skills: add <folder>/<name>/" + SkillStore.FILE + " (see plugins/skills/README.md)");
      return;
    }
    for (Skill s : all.values()) {
      String d = s.description().replace('\n', ' ');
      if (d.length() > 80) d = d.substring(0, 77) + "...";
      call.out().println(s.name() + "  [" + s.source() + "]  " + d);
      String f = flags(ctx, s);
      if (!f.isEmpty()) call.out().println("  " + f);
    }
  }

  /** What differs from a plain skill: who may run it, a command hiding it, keys left out. */
  private static String flags(Context ctx, Skill s) {
    StringBuilder out = new StringBuilder();
    if (!s.model()) out.append("only the user runs it; ");
    if (!s.user()) out.append("only the agent loads it; ");
    else if (Commands.find(ctx, s.name()).isPresent()) {
      out.append("/").append(s.name()).append(" runs the command of that name, rename the folder to run it; ");
    } else {
      out.append("/").append(s.name()).append(s.argumentHint() == null ? "" : " " + s.argumentHint()).append("; ");
    }
    if (!s.ignored().isEmpty()) out.append("ignored: ").append(String.join(", ", s.ignored())).append("; ");
    return out.isEmpty() ? "" : out.substring(0, out.length() - 2);
  }

  private record Load(Skills skills, int max) implements Tool {
    @Override
    public ToolSpec spec() {
      return new ToolSpec(TOOL, "Load a skill listed in the system prompt (section Skills): its instructions, its "
          + "base directory and its files. Call it before working on a task that matches the skill, then follow "
          + "it. `args` fills the skill's $ARGUMENTS. `file` reads one of the skill's files instead.",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "name":{"type":"string","description":"the skill's name"},
                "args":{"type":"string","description":"arguments for the skill, if it takes any"},
                "file":{"type":"string","description":"a file of the skill, relative to its base directory"}},
               "required":["name"],"additionalProperties":false}"""));
    }

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      Map<String, Skill> all = skills.of(tc.origin());
      String name = args.path("name").asText("").strip();
      Skill s = all.get(name);
      if (s == null) {
        String names = String.join(", ", all.values().stream().filter(Skill::model).map(Skill::name).toList());
        return ToolResult.error("no skill " + name + (names.isEmpty() ? "" : "; skills: " + names));
      }
      if (!s.model()) return ToolResult.error("skill " + name + " is run by the user only (/" + name + ")");
      if (args.hasNonNull("file")) {
        try {
          return ToolResult.ok(SkillStore.readFile(s, args.get("file").asText(), max));
        } catch (SkillException e) {
          return ToolResult.error(e.getMessage());
        }
      }
      return ToolResult.ok(SkillStore.render(s, args.path("args").asText(""), max));
    }
  }
}

package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import panic.llm.Message;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.tools.Resource;
import panic.tools.ToolResult;

/**
 * E1 of the context plan: the clearing policies on a simulated agent, deterministic, no model. It measures the
 * mechanism, not the model: the agent is the behaviour seen in the 2026-11-18 trace (it needs every file of its
 * working set in view at once to make its change, and reads again whatever it no longer sees), and the numbers say
 * how much each policy makes such an agent read, pay, and whether it gets there at all.
 *
 * <p>The agent works through subtasks. Each needs a working set of W files (half of it shared with the previous
 * one) and, in each file, a region of about 30% of its lines. Each step it reads the first file of the set it does
 * not see (the whole file; only the region when an outline of the file is in view), sometimes runs a command (800
 * chars of output), and once it sees the whole set it edits one of the files (a new version) and moves on. A run
 * that has not finished after {@link #MAX_STEPS} steps failed. Tokens are chars / 4 + 4 per message; a request's
 * cached part is its longest prefix of whole messages equal to the previous request's (how prompt caches work).
 *
 * <p>Policies: P0 is the code that failed (cleared every result but the newest 3 once the uncleared turn passed half
 * the budget); P1 is hysteresis only (clearing by age, the baseline other harnesses use, fixed); P1h is P1 at the old
 * trigger (half the budget), to separate the fix from the threshold; P2 adds resources, P4 compact forms, P5 summaries
 * of the turn's older steps once clearing cannot make room ({@link TurnCompaction}, the summary a fixed 600 tokens plus
 * the manifest). Pinning (P3) was measured here and removed: it never changed an outcome, since the order of clearing
 * cannot save a working set that does not fit. Writes {@code build/reports/context-bench.md}. The assertions check
 * that the simulation reproduces what was observed (P0 loops on the trace's working set, nothing loops when all
 * fits) and that P5 keeps every request under the budget; everything else is reported, not asserted.
 */
class ContextPolicyBench {

  static final int MAX_STEPS = 300;
  /** The night's first request: system prompt and tools, 3418 tokens. */
  static final int SYSTEM_TOKENS = 2_900;
  static final int TOOL_TOKENS = 520;
  static final int KEEP = 3;

  /**
   * read_file output sizes (content + line numbers) of the 13 files the trace read: its 7-file cycle first (the
   * working set), then the 6 other files it read while finding its way.
   */
  static final int[] TRACE = {555, 4985, 3085, 5436, 2440, 10323, 3761, 1014, 299, 3175, 4140, 3896, 1654};

  /**
   * Characters per token of that night's requests: (15273 - 3418) prompt tokens for 14200 chars of results at step 7
   * (Vietnamese text and line-number prefixes cost DeepSeek's tokenizer far more than English code).
   */
  static final double TRACE_CHARS_PER_TOKEN = 1.2;
  static final int[] POOL = {3125, 2888, 12871, 3943, 1694, 5480, 3198, 2424, 4210, 2491, 2630, 3648, 11680, 3815,
      299, 8584, 479, 2360, 2430, 414, 15239, 7300, 9653, 2709, 19267, 2371, 7188, 1679, 4780, 6120};

  enum Policy {
    P0("P0 legacy (all but 3, uncleared estimate, 1/2)"),
    P1H("P1h hysteresis by age, trigger 1/2"),
    P1("P1 hysteresis by age, trigger 3/4"),
    P2("P2 + resources"),
    P4("P4 + outlines"),
    P5("P5 + turn summaries (all)");

    final String label;

    Policy(String label) {
      this.label = label;
    }

    ContextEditor.Options options() {
      return switch (this) {
        case P0, P1H, P1 -> ContextEditor.Options.NONE;
        case P2 -> new ContextEditor.Options(true, false);
        case P4, P5 -> ContextEditor.Options.ALL;
      };
    }
  }

  /**
   * {@code explore}: files read once each, in order, before the work (what the agent reads to find its way);
   * then the working sets: the first {@code workingSet} files for one subtask, or drawn sets for several.
   */
  record Scenario(String name, int[] sizes, int explore, int workingSet, int subtasks, int budget, double execRate,
      double charsPerToken) {
  }

  record Outcome(boolean done, int steps, int reads, int refetches, long prompt, long uncached, int peak, int cleared,
      int summaries) {
  }

  static int tokens(Message m, double charsPerToken) {
    long chars = m.content().length();
    for (ToolCall c : m.toolCalls()) chars += c.name().length() + c.arguments().length();
    return 4 + (int) Math.ceil(chars / charsPerToken);
  }

  /** One run of the simulated agent under {@code policy}. */
  static Outcome run(Scenario sc, Policy policy) {
    Random random = new Random(42);
    int files = sc.sizes().length;
    int[] version = new int[files];
    // working sets: each shares half of the previous one, the rest drawn from the pool
    List<List<Integer>> sets = new ArrayList<>();
    List<Integer> previous = List.of();
    for (int k = 0; k < sc.subtasks(); k++) {
      if (sc.explore() > 0 && k == 0) {
        List<Integer> first = new ArrayList<>();
        for (int f = 0; f < sc.workingSet(); f++) first.add(f);
        sets.add(first);
        previous = first;
        continue;
      }
      List<Integer> set = new ArrayList<>(previous.subList(0, Math.min(previous.size(), sc.workingSet() / 2)));
      List<Integer> others = new ArrayList<>();
      for (int f = 0; f < files; f++) if (!set.contains(f)) others.add(f);
      java.util.Collections.shuffle(others, random);
      for (int f : others) {
        if (set.size() >= sc.workingSet()) break;
        set.add(f);
      }
      sets.add(set);
      previous = set;
    }
    // the region each file is needed for: 30% of its lines, somewhere
    int[] lines = new int[files];
    int[] regionFrom = new int[files];
    int[] regionTo = new int[files];
    for (int f = 0; f < files; f++) {
      lines[f] = Math.max(10, sc.sizes()[f] / 40);
      int len = Math.max(1, lines[f] * 3 / 10);
      regionFrom[f] = 1 + random.nextInt(Math.max(1, lines[f] - len));
      regionTo[f] = regionFrom[f] + len - 1;
    }

    ContextEditor editor = new ContextEditor(policy.options());
    int trigger = policy == Policy.P1 || policy.ordinal() >= Policy.P2.ordinal() ? sc.budget() / 4 * 3 : sc.budget() / 2;
    int target = trigger / 3 * 2;
    List<Message> turn = new ArrayList<>();
    turn.add(Message.user("do the task"));
    Map<String, Integer> callFile = new HashMap<>();
    Set<String> readOnce = new HashSet<>();
    List<Message> lastRequest = List.of();
    long prompt = 0;
    long uncached = 0;
    int peak = 0;
    int reads = 0;
    int refetches = 0;
    int clearedMax = 0;
    int subtask = 0;
    int step = 0;
    int callNo = 0;
    int explored = 0;
    int turnCut = 0;
    String progress = null;
    int summaries = 0;
    ContextEditor.Estimator est = m -> tokens(m, sc.charsPerToken());
    int toolTokens = TOOL_TOKENS;
    while (step < MAX_STEPS && subtask < sets.size()) {
      step++;
      // the request as the policy sends it
      List<Message> request = request(sc, turn, turnCut, progress);
      int cleared;
      if (policy == Policy.P0) {
        cleared = legacy(request, trigger, est, toolTokens);
      } else {
        ContextEditor.Applied a = editor.apply(request, est, toolTokens, trigger, target, KEEP);
        cleared = a.cleared();
        int from = Math.max(1, turnCut);
        int cut = policy == Policy.P5 && a.estimate() > trigger
            ? TurnCompaction.cut(turn.subList(from, turn.size()), est, target / 2) : 0;
        if (cut > 0) {
          List<String> ids = new ArrayList<>();
          for (Message m : turn.subList(from, from + cut)) if (m.role() == Role.TOOL) ids.add(m.toolCallId());
          editor.forget(ids);
          progress = TurnCompaction.progress("p".repeat((int) (600 * sc.charsPerToken())),
              editor.manifest() + "\n\n" + editor.outlines(est, target / 4));
          turnCut = from + cut;
          summaries++;
          request = request(sc, turn, turnCut, progress);
          cleared = editor.apply(request, est, toolTokens, trigger, target, KEEP).cleared();
        }
      }
      clearedMax = Math.max(clearedMax, cleared);
      int total = toolTokens;
      for (Message m : request) total += est.estimate(m);
      int same = 0;
      while (same < Math.min(request.size(), lastRequest.size()) && request.get(same).equals(lastRequest.get(same))) {
        same++;
      }
      int cachedTokens = toolTokens;
      for (int i = 0; i < same; i++) cachedTokens += est.estimate(request.get(i));
      prompt += total;
      uncached += Math.max(0, total - cachedTokens);
      peak = Math.max(peak, total);
      lastRequest = request;

      // what the agent sees in full (or as a region) in this request
      List<Integer> set = sets.get(subtask);
      Integer missing = null;
      boolean outlined = false;
      if (explored < sc.explore()) {
        missing = explored++; // finding its way: every file once, whatever it sees
        set = List.of();
      }
      for (int f : set) {
        if (!visible(request, callFile, f, version[f], regionFrom[f], regionTo[f])) {
          missing = f;
          outlined = outlineOf(request, callFile, f);
          break;
        }
      }
      String id = "c" + (++callNo);
      if (missing == null) {
        int f = set.get(subtask % set.size());
        version[f]++;
        String text = "edited f" + f;
        turn.add(Message.assistant("", List.of(new ToolCall(id, "edit_file", "{\"path\":\"f" + f + "\"}"))));
        ToolResult r = ToolResult.ok(text).about(Resource.write("file:f" + f, "v" + version[f]), null);
        record(editor, turn, id, "edit_file", r, step);
        callFile.put(id, -1);
        subtask++;
        continue;
      }
      int f = missing;
      int from = outlined ? regionFrom[f] : 1;
      int to = outlined ? regionTo[f] : lines[f];
      int chars = outlined ? Math.max(1, sc.sizes()[f] * (to - from + 1) / lines[f]) : sc.sizes()[f];
      String unit = "f" + f + "v" + version[f] + "#" + from + "-" + to + " ";
      String body = unit.repeat(chars / unit.length() + 2).substring(0, Math.max(chars, unit.length()));
      String compact = policy.ordinal() >= Policy.P4.ordinal() ? "outline".repeat(Math.max(1, sc.sizes()[f] / 7 / 7)) + " outline:f" + f
          : null;
      turn.add(Message.assistant("", List.of(new ToolCall(id, "read_file", "{\"path\":\"f" + f + "\"}"))));
      ToolResult r = ToolResult.ok(body).about(Resource.view("file:f" + f, "v" + version[f], from, to), compact);
      String key = f + "@" + version[f];
      if (!readOnce.add(key)) refetches++;
      reads++;
      callFile.put(id, f);
      record(editor, turn, id, "read_file", r, step);
      if (random.nextDouble() < sc.execRate()) {
        String eid = "c" + (++callNo);
        turn.add(Message.assistant("", List.of(new ToolCall(eid, "exec", "{\"command\":\"ls\"}"))));
        record(editor, turn, eid, "exec", ToolResult.ok(("out" + step + " ").repeat(160)), step);
        callFile.put(eid, -1);
      }
    }
    return new Outcome(subtask >= sets.size(), step, reads, refetches, prompt, uncached, peak, clearedMax, summaries);
  }

  /** The request before the policy clears anything: system, then the turn (its summarised steps as progress). */
  private static List<Message> request(Scenario sc, List<Message> turn, int turnCut, String progress) {
    List<Message> request = new ArrayList<>();
    request.add(Message.system("s".repeat((int) (SYSTEM_TOKENS * sc.charsPerToken()))));
    request.addAll(TurnCompaction.view(turn, 1, turnCut, progress));
    return request;
  }

  private static int fIndex(Message m, Map<String, Integer> callFile) {
    Integer f = callFile.get(m.toolCallId());
    return f == null ? -1 : f;
  }

  /** True when an outline of file {@code f} is in view: a cleared read's, or one a turn summary kept. */
  private static boolean outlineOf(List<Message> request, Map<String, Integer> callFile, int f) {
    String mark = " outline:f" + f;
    return request.stream().anyMatch(m -> m.role() == Role.TOOL && fIndex(m, callFile) == f
        && m.content().endsWith(mark)
        || m.role() == Role.USER && (m.content().contains(mark + "\n") || m.content().endsWith(mark)));
  }

  /** True when the request shows lines {@code from}..{@code to} of version {@code v} of file {@code f}. */
  private static boolean visible(List<Message> request, Map<String, Integer> callFile, int f, int v, int from, int to) {
    for (Message m : request) {
      if (m.role() != Role.TOOL || fIndex(m, callFile) != f) continue;
      String c = m.content();
      String prefix = "f" + f + "v" + v + "#";
      if (!c.startsWith(prefix)) continue;
      String[] range = c.substring(prefix.length(), c.indexOf(' ')).split("-");
      if (Integer.parseInt(range[0]) <= from && Integer.parseInt(range[1]) >= to) return true;
    }
    return false;
  }

  private static void record(ContextEditor editor, List<Message> turn, String id, String tool, ToolResult r, int step) {
    String pointer = editor.unchanged(tool, r);
    ToolResult shown = pointer == null ? r : new ToolResult(pointer, false);
    editor.record(id, tool, shown, shown.content(), step);
    turn.add(Message.tool(id, shown.content()));
  }

  /** The policy that failed: every result but the newest 3 cleared once the uncleared request passes the trigger. */
  private static int legacy(List<Message> messages, int trigger, ContextEditor.Estimator est, int toolTokens) {
    int total = toolTokens;
    for (Message m : messages) total += est.estimate(m);
    if (total <= trigger) return 0;
    int seen = 0;
    int count = 0;
    for (int i = messages.size() - 1; i >= 0; i--) {
      Message m = messages.get(i);
      if (m.role() != Role.TOOL) continue;
      if (++seen <= KEEP) continue;
      String placeholder = "[tool result cleared to save context (" + m.content().length() + " chars); call the tool "
          + "again if you need it]";
      if (m.content().length() <= placeholder.length()) continue;
      messages.set(i, Message.tool(m.toolCallId(), placeholder));
      count++;
    }
    return count;
  }

  static List<Scenario> scenarios() {
    List<Scenario> out = new ArrayList<>();
    out.add(new Scenario("trace replay: read 13, then need 7 at once; 32K, 1.2 chars/token", TRACE, 13, 7, 1,
        32_000, 0.0, TRACE_CHARS_PER_TOKEN));
    out.add(new Scenario("trace replay at 128K", TRACE, 13, 7, 1, 128_000, 0.0, TRACE_CHARS_PER_TOKEN));
    // a long turn of small changes: every working set fits, but the steps' residue alone outgrows the budget
    out.add(new Scenario("long turn: W=3, 32K, 1.2 chars/token, 60 subtasks", POOL, 0, 3, 60, 32_000, 0.3,
        TRACE_CHARS_PER_TOKEN));
    for (double cpt : new double[] {4.0, 1.5}) {
      for (int budget : new int[] {32_000, 128_000}) {
        for (int w : new int[] {3, 7, 15}) {
          out.add(new Scenario(String.format(Locale.ROOT, "W=%d, %dK, %.1f chars/token, 4 subtasks", w, budget / 1000,
              cpt), POOL, 0, w, 4, budget, 0.3, cpt));
        }
      }
    }
    return out;
  }

  @Test
  void report() throws IOException {
    StringBuilder md = new StringBuilder("# Context policy bench (E1)\n\n")
        .append("Simulated agent, see ContextPolicyBench's javadoc for the model. `done` = finished within ")
        .append(MAX_STEPS).append(" steps; `refetch` = reads of a file version already read; tokens in thousands; ")
        .append("`uncached` = prompt tokens outside the previous request's prefix.\n\n")
        .append("| scenario | policy | done | steps | reads | refetch | prompt K | uncached K | peak K | summaries |\n")
        .append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
    Map<String, Outcome> byKey = new HashMap<>();
    for (Scenario sc : scenarios()) {
      for (Policy p : Policy.values()) {
        Outcome o = run(sc, p);
        byKey.put(sc.name() + "|" + p, o);
        md.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %d | %d%% | %.0f | %.0f | %s | %d |%n",
            sc.name(), p.label, o.done() ? "yes" : "**no**", o.steps(), o.reads(),
            o.reads() == 0 ? 0 : Math.round(100.0 * o.refetches() / o.reads()), o.prompt() / 1000.0,
            o.uncached() / 1000.0, String.format(Locale.ROOT, o.peak() > sc.budget() ? "**%.1f**" : "%.1f",
                o.peak() / 1000.0), o.summaries()));
      }
    }
    Path out = Path.of("build", "reports", "context-bench.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
    System.out.println(md);

    // the simulation must reproduce what was observed, or its other numbers mean nothing
    String trace = scenarios().get(0).name();
    Outcome legacy = byKey.get(trace + "|" + Policy.P0);
    assertFalse(legacy.done(), "P0 loops on the trace's working set, as it did that night");
    assertTrue(legacy.refetches() > 100, "and re-reads the same files: " + legacy.refetches());
    // and where everything fits, no policy may cost a read more than reading each file once
    String roomy = scenarios().get(1).name();
    for (Policy p : Policy.values()) {
      Outcome o = byKey.get(roomy + "|" + p);
      assertTrue(o.done(), p + " finishes when everything fits");
      assertEquals(13, o.reads(), p + " reads each file once when everything fits");
    }
    // summarising the turn's older steps bounds every request, whatever the turn's length
    for (Scenario sc : scenarios()) {
      Outcome o = byKey.get(sc.name() + "|" + Policy.P5);
      assertTrue(o.peak() <= sc.budget(), sc.name() + ": P5 peak " + o.peak() + " over " + sc.budget());
    }
  }
}

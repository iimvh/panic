package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import panic.llm.Message;
import panic.llm.ToolCall;
import panic.tools.Resource;
import panic.tools.ToolResult;

/** What {@link ContextEditor} clears, in which order, and what it leaves in place. */
class ContextEditorTest {

  /** One token per character: sizes in the tests read as tokens. */
  private static final ContextEditor.Estimator CHARS = m -> m.content().length();

  /** A turn being built: the messages a request would carry, and the editor that sees each result. */
  private static final class Turn {
    final ContextEditor editor;
    final List<Message> messages = new ArrayList<>(List.of(Message.user("q")));
    int step;

    Turn(ContextEditor.Options options) {
      editor = new ContextEditor(options);
    }

    /** Adds a call and its result as the chat would; returns the content the model reads. */
    String result(String id, String tool, ToolResult r) {
      step++;
      messages.add(Message.assistant("", List.of(new ToolCall(id, tool, "{}"))));
      String pointer = editor.unchanged(tool, r);
      ToolResult shown = pointer == null ? r : new ToolResult(pointer, false);
      editor.record(id, tool, shown, shown.content(), step);
      messages.add(Message.tool(id, shown.content()));
      return shown.content();
    }

    String read(String id, String file, String version, int from, int to, int size) {
      return result(id, "read_file", ToolResult.ok("x".repeat(size)).about(
          Resource.view("file:" + file, version, from, to), "outline of " + file));
    }

    /** The request as it would be sent, after the editor. */
    List<Message> request(int trigger, int target, int keep) {
      List<Message> copy = new ArrayList<>(messages);
      editor.apply(copy, CHARS, 0, trigger, target, keep);
      return copy;
    }

    static String content(List<Message> request, String id) {
      return request.stream().filter(m -> id.equals(m.toolCallId())).findFirst().orElseThrow().content();
    }
  }

  @Test
  void underTheTriggerNothingIsCleared() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    for (int i = 1; i <= 5; i++) t.result("c" + i, "exec", ToolResult.ok("o".repeat(100)));
    List<Message> request = t.request(1_000, 600, 1);
    assertTrue(request.stream().noneMatch(m -> m.content().startsWith("[")), request.toString());
  }

  @Test
  void overTheTriggerOnlyEnoughIsClearedOldestFirst() {
    Turn t = new Turn(ContextEditor.Options.NONE);
    for (int i = 1; i <= 6; i++) t.result("c" + i, "exec", ToolResult.ok(i + "o".repeat(999)));
    // 6 x 1000 over 5000: clear the oldest (each leaves a placeholder of about 150) down to 4000: three of them
    List<Message> request = t.request(5_000, 4_000, 1);
    for (int i = 1; i <= 3; i++) assertTrue(Turn.content(request, "c" + i).startsWith("[exec result cleared"));
    for (int i = 4; i <= 6; i++) assertTrue(Turn.content(request, "c" + i).startsWith(i + "o"), "kept: " + i);
  }

  @Test
  void aClearedResultStaysClearedSoThePrefixIsStable() {
    Turn t = new Turn(ContextEditor.Options.NONE);
    for (int i = 1; i <= 6; i++) t.result("c" + i, "exec", ToolResult.ok(i + "o".repeat(999)));
    List<Message> first = t.request(5_000, 4_000, 1);
    t.result("c7", "exec", ToolResult.ok("small"));
    List<Message> second = t.request(5_000, 4_000, 1);
    assertEquals(first, second.subList(0, first.size()), "the next request only adds to the previous one");
    // even a trigger nobody reaches keeps what was cleared: the prefix must not change back
    List<Message> third = t.request(100_000, 50_000, 1);
    assertTrue(Turn.content(third, "c1").startsWith("[exec result cleared"));
  }

  @Test
  void theNewestResultsAreNeverCleared() {
    Turn t = new Turn(ContextEditor.Options.NONE);
    for (int i = 1; i <= 4; i++) t.result("c" + i, "exec", ToolResult.ok("o".repeat(500)));
    List<Message> request = t.request(100, 50, 3);
    assertTrue(Turn.content(request, "c1").startsWith("["));
    for (int i = 2; i <= 4; i++) assertFalse(Turn.content(request, "c" + i).startsWith("["), "kept: " + i);
  }

  @Test
  void aClearedViewOfAnOlderVersionSaysItIsStale() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    t.read("r1", "a.js", "v1", 1, 50, 300);
    t.read("r2", "b.js", "v1", 1, 50, 300);
    t.result("w1", "edit_file", ToolResult.ok("edited a.js").about(Resource.write("file:a.js", "v2"), null));
    t.result("e2", "exec", ToolResult.ok("o".repeat(300)));
    List<Message> request = t.request(800, 750, 1);
    assertTrue(Turn.content(request, "r1").startsWith("[read_file result cleared: a.js changed after this read"),
        Turn.content(request, "r1"));
    assertFalse(Turn.content(request, "r2").startsWith("["), "only enough is cleared, oldest first");
  }

  @Test
  void anUnchangedReReadGetsAPointerWhileTheViewIsShown() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    t.read("r1", "a.js", "v1", 1, 100, 300);
    String again = t.read("r2", "a.js", "v1", 20, 30, 50);
    assertTrue(again.startsWith("[unchanged: a.js lines 20-30 are exactly as in your read_file result of step 1"),
        again);
    assertEquals(1, t.editor.unchangedHits());
    // once the view is cleared, a re-read shows the text again, and counts as a miss
    t.result("e", "exec", ToolResult.ok("o".repeat(2_000)));
    t.request(1_000, 500, 1);
    String third = t.read("r3", "a.js", "v1", 1, 100, 300);
    assertEquals("x".repeat(300), third);
    assertEquals(1, t.editor.refetches());
  }

  @Test
  void aChangedFileIsShownAgain() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    t.read("r1", "a.js", "v1", 1, 100, 300);
    t.result("w", "edit_file", ToolResult.ok("edited").about(Resource.write("file:a.js", "v2"), null));
    assertEquals("x".repeat(300), t.read("r2", "a.js", "v2", 1, 100, 300));
    assertEquals("x".repeat(300), t.read("r3", "b.js", "v1", 1, 100, 300));
  }

  @Test
  void aMissIsClearedByAgeLikeAnyResult() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    t.read("a1", "a.js", "v1", 1, 100, 300);
    t.read("b1", "b.js", "v1", 1, 100, 300);
    t.request(500, 300, 1);
    t.read("a2", "a.js", "v1", 1, 100, 300);
    assertEquals(1, t.editor.refetches());
    t.read("c1", "c.js", "v1", 1, 100, 300);
    t.read("d1", "d.js", "v1", 1, 100, 300);
    List<Message> request = t.request(1_000, 900, 1);
    assertTrue(Turn.content(request, "a2").startsWith("["), "no pinning: the order cannot save a working set");
  }

  @Test
  void aClearedViewLeavesItsOutline() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    t.read("r1", "a.js", "v1", 1, 100, 500);
    t.result("e", "exec", ToolResult.ok("o".repeat(500)));
    List<Message> request = t.request(800, 600, 1);
    String cut = Turn.content(request, "r1");
    assertTrue(cut.startsWith("[read_file result cleared to save context (500 chars); its outline is kept]\n"), cut);
    assertTrue(cut.endsWith("outline of a.js"), cut);
    Turn without = new Turn(new ContextEditor.Options(true, false));
    without.read("r1", "a.js", "v1", 1, 100, 500);
    without.result("e", "exec", ToolResult.ok("o".repeat(500)));
    assertFalse(Turn.content(without.request(800, 600, 1), "r1").contains("outline"));
  }

  @Test
  void resultsOfEarlierTurnsAreClearedLikeOutputs() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    t.messages.add(0, Message.tool("old", "o".repeat(400))); // from history: the editor never saw it
    t.read("r1", "a.js", "v1", 1, 100, 400);
    t.result("e", "exec", ToolResult.ok("small"));
    List<Message> request = t.request(700, 600, 1);
    assertTrue(Turn.content(request, "old").startsWith("[tool result cleared"));
    assertFalse(Turn.content(request, "r1").startsWith("["));
  }

  @Test
  void pointersAreOnlyForUnchangedSuccessfulViews() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    t.read("r1", "a.js", "v1", 1, 100, 300);
    assertNotNull(t.editor.unchanged("read_file", ToolResult.ok("y").about(Resource.view("file:a.js", "v1", 1, 100), null)));
    assertNull(t.editor.unchanged("read_file", ToolResult.error("no").about(Resource.view("file:a.js", "v1", 1, 100), null)));
    assertNull(new ContextEditor(ContextEditor.Options.NONE).unchanged("read_file",
        ToolResult.ok("y").about(Resource.view("file:a.js", "v1", 1, 100), null)));
  }

  @Test
  void aForgottenResultIsNoLongerPointedTo() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    t.read("r1", "a.js", "v1", 1, 100, 300);
    t.editor.forget(List.of("r1"));
    assertEquals("x".repeat(300), t.read("r2", "a.js", "v1", 1, 100, 300), "summarised away: show the text again");
    assertEquals(1, t.editor.refetches(), "and it counts as a miss");
  }

  @Test
  void theManifestListsWhatTheToolsDeclared() {
    Turn t = new Turn(ContextEditor.Options.ALL);
    t.read("r1", "src/a.js", "v1", 1, 100, 300);
    t.result("w1", "edit_file", ToolResult.ok("edited").about(Resource.write("file:src/a.js", "v2"), null));
    t.result("w2", "edit_file", ToolResult.ok("edited").about(Resource.write("file:src/a.js", "v3"), null));
    t.result("e", "exec", ToolResult.ok("ok"));
    String manifest = t.editor.manifest();
    assertTrue(manifest.contains("Files changed (path @ version): src/a.js @ v3"), manifest);
    assertTrue(manifest.contains("Files read: src/a.js"), manifest);
    assertTrue(manifest.contains("read_file \u00d71, edit_file \u00d72, exec \u00d71"), manifest);
  }
}

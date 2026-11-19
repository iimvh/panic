package panic.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Root;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.util.CancelToken;
import panic.llm.ToolCall;

class FilesPluginTest {

  private final Root root = Context.root("t");

  @TempDir
  Path dir;
  Path workspace;
  ConfigHandle<FilesConfig> files;

  @BeforeEach
  void setUp() throws IOException {
    workspace = Files.createDirectory(dir.resolve("ws"));
    Files.writeString(workspace.resolve("a.txt"), "one\ntwo\nthree\n");
    Files.createDirectory(workspace.resolve("sub"));
    Files.writeString(workspace.resolve("sub/b.txt"), "inside\n");
    Files.writeString(dir.resolve("secret.txt"), "outside\n");
    files = root.plugin("files", new FilesPlugin(), new FilesConfig(workspace.toString(), 0, 0, 0));
    // writes are asked by default; these tests are about confinement, not approval
    root.plugin("permissions", new PermissionsPlugin(), new PermissionsConfig(Map.of("write", "allow"), null, 0));
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  /** The result as the model reads it: what it is about and its outline are checked by their own tests. */
  private ToolResult call(String name, String args) {
    ToolResult r = raw(name, args);
    return new ToolResult(r.content(), r.isError(), r.usage());
  }

  private ToolResult raw(String name, String args) {
    return Tools.call(root, new ToolCall("id", name, args), new ToolContext("c", new CancelToken()));
  }

  private void assertRefused(ToolResult r, String why) {
    assertTrue(r.isError(), r.content());
    assertTrue(r.content().contains(why), r.content());
  }

  @Test
  void readsFilesWithOffsetAndLimit() {
    assertEquals(ToolResult.ok("     1\tone\n     2\ttwo\n     3\tthree\n"), call("read_file", "{\"path\":\"a.txt\"}"));
    assertEquals(ToolResult.ok("     1\tinside\n"), call("read_file", "{\"path\":\"sub/../sub/b.txt\"}"));
    assertEquals(ToolResult.ok("     2\ttwo\n… [1 more line; continue with offset=3]"),
        call("read_file", "{\"path\":\"a.txt\",\"offset\":2,\"limit\":1}"));
    assertRefused(call("read_file", "{\"path\":\"a.txt\",\"offset\":9}"), "has only 3 lines");
    assertRefused(call("read_file", "{\"path\":\"nope.txt\"}"), "not found: nope.txt");
    assertRefused(call("read_file", "{\"path\":\"sub\"}"), "is a directory");
  }

  @Test
  void cutsLongFiles() throws IOException {
    files.update(new FilesConfig(workspace.toString(), 25, 0, 0));
    assertEquals(ToolResult.ok("     1\tone\n     2\ttwo\n… [truncated at 25 chars; continue with offset=3]"),
        call("read_file", "{\"path\":\"a.txt\"}"));
  }

  @Test
  void refusesBinaryFiles() throws IOException {
    Files.write(workspace.resolve("bin"), new byte[] {1, 0, 2});
    assertRefused(call("read_file", "{\"path\":\"bin\"}"), "binary");
  }

  @Test
  void listsDirectories() throws IOException {
    assertEquals(ToolResult.ok("a.txt\nsub/\n"), call("list_dir", "{}"));
    assertEquals(ToolResult.ok("b.txt\n"), call("list_dir", "{\"path\":\"sub\"}"));
    files.update(new FilesConfig(workspace.toString(), 0, 1, 0));
    assertEquals(ToolResult.ok("a.txt\n… 1 more\n"), call("list_dir", "{}"));
    assertRefused(call("list_dir", "{\"path\":\"a.txt\"}"), "not a directory");
  }

  /** A path as a JSON string (backslashes of Windows paths escaped). */
  private static String quoted(Path path) {
    return panic.util.Json.MAPPER.valueToTree(path.toString()).toString();
  }

  @Test
  void staysInsideTheWorkspace() throws IOException {
    assertRefused(call("read_file", "{\"path\":\"../secret.txt\"}"), "outside the workspace");
    assertRefused(call("read_file", "{\"path\":" + quoted(dir.resolve("secret.txt")) + "}"), "outside the workspace");
    assertRefused(call("list_dir", "{\"path\":\"..\"}"), "outside the workspace");

    Files.createSymbolicLink(workspace.resolve("link.txt"), dir.resolve("secret.txt"));
    Files.createSymbolicLink(workspace.resolve("up"), dir);
    assertRefused(call("read_file", "{\"path\":\"link.txt\"}"), "outside the workspace");
    assertRefused(call("read_file", "{\"path\":\"up/secret.txt\"}"), "outside the workspace");
  }

  @Test
  void rootFollowsTheConfig() {
    files.update(new FilesConfig(dir.toString(), 0, 0, 0));
    assertEquals(ToolResult.ok("     1\toutside\n"), call("read_file", "{\"path\":\"secret.txt\"}"));
    files.update(new FilesConfig(dir.resolve("missing").toString(), 0, 0, 0));
    assertRefused(call("list_dir", "{}"), "workspace root does not exist");
  }

  @Test
  void rejectsUnknownArguments() {
    assertRefused(call("read_file", "{\"path\":\"a.txt\",\"mode\":\"w\"}"), "mode: unknown property");
  }

  @Test
  void writesFiles() throws IOException {
    assertEquals(ToolResult.ok("wrote 3 bytes to new.txt"), call("write_file", "{\"path\":\"new.txt\",\"content\":\"abc\"}"));
    assertEquals("abc", Files.readString(workspace.resolve("new.txt")));

    call("write_file", "{\"path\":\"a.txt\",\"content\":\"replaced\"}");
    assertEquals("replaced", Files.readString(workspace.resolve("a.txt")));

    assertEquals(ToolResult.ok("appended 2 bytes to a.txt"),
        call("write_file", "{\"path\":\"a.txt\",\"content\":\"!!\",\"append\":true}"));
    assertEquals("replaced!!", Files.readString(workspace.resolve("a.txt")));

    call("write_file", "{\"path\":\"x/y/z.txt\",\"content\":\"deep\"}");
    assertEquals("deep", Files.readString(workspace.resolve("x/y/z.txt")));
  }

  @Test
  void writesStayInsideTheWorkspace() throws IOException {
    assertRefused(call("write_file", "{\"path\":\"../evil.txt\",\"content\":\"x\"}"), "outside the workspace");
    assertRefused(call("write_file", "{\"path\":" + quoted(dir.resolve("evil.txt")) + ",\"content\":\"x\"}"),
        "outside the workspace");
    assertRefused(call("write_file", "{\"path\":\".\",\"content\":\"x\"}"), "outside the workspace");

    Files.createSymbolicLink(workspace.resolve("up"), dir);
    assertRefused(call("write_file", "{\"path\":\"up/evil.txt\",\"content\":\"x\"}"), "outside the workspace");
    assertRefused(call("write_file", "{\"path\":\"up/new/evil.txt\",\"content\":\"x\"}"), "outside the workspace");

    Files.createSymbolicLink(workspace.resolve("link.txt"), dir.resolve("secret.txt"));
    assertRefused(call("write_file", "{\"path\":\"link.txt\",\"content\":\"x\"}"), "symbolic link");
    Files.createSymbolicLink(workspace.resolve("inner.txt"), workspace.resolve("a.txt"));
    assertRefused(call("write_file", "{\"path\":\"inner.txt\",\"content\":\"x\"}"), "symbolic link");

    assertRefused(call("write_file", "{\"path\":\"sub\",\"content\":\"x\"}"), "is a directory");
    assertRefused(call("write_file", "{\"path\":\"a.txt/b\",\"content\":\"x\"}"), "not a directory");

    assertEquals("outside\n", Files.readString(dir.resolve("secret.txt")));
    assertTrue(Files.notExists(dir.resolve("evil.txt")));
    assertTrue(Files.notExists(dir.resolve("new")));
  }

  @Test
  void refusesOversizedWrites() {
    files.update(new FilesConfig(workspace.toString(), 0, 0, 4));
    assertRefused(call("write_file", "{\"path\":\"big.txt\",\"content\":\"12345\"}"), "the most write_file takes is 4");
    assertTrue(Files.notExists(workspace.resolve("big.txt")));
  }

  private ToolResult edit(String path, String oldStr, String newStr, Boolean all) {
    var args = panic.util.Json.object().put("path", path).put("old_str", oldStr).put("new_str", newStr);
    if (all != null) args.put("replace_all", all);
    return call("edit_file", args.toString());
  }

  @Test
  void editReplacesAStringThatOccursOnce() throws IOException {
    assertEquals(ToolResult.ok("edited a.txt: replaced 1 occurrence (first at line 2)"), edit("a.txt", "two\n", "2\n", null));
    assertEquals("one\n2\nthree\n", Files.readString(workspace.resolve("a.txt")));
    edit("a.txt", "one\n", "", null);
    assertEquals("2\nthree\n", Files.readString(workspace.resolve("a.txt")), "an empty new_str deletes");
  }

  @Test
  void editRefusesWhatItCannotPlaceExactly() throws IOException {
    assertRefused(edit("a.txt", "four", "4", null), "old_str not found in a.txt");
    assertRefused(edit("a.txt", "  two", "2", null), "read_file the part again");
    Files.writeString(workspace.resolve("dup.txt"), "x = 1\nx = 1\nx = 1\n");
    assertRefused(edit("dup.txt", "x = 1", "x = 2", null), "occurs 3 times");
    assertEquals("x = 1\nx = 1\nx = 1\n", Files.readString(workspace.resolve("dup.txt")), "nothing changed");
    assertRefused(edit("a.txt", "", "x", null), "old_str is empty");
    assertRefused(edit("a.txt", "two", "two", null), "nothing to change");
    assertRefused(edit("missing.txt", "a", "b", null), "not found: missing.txt");
    assertTrue(Files.notExists(workspace.resolve("missing.txt")));
    assertRefused(edit("sub", "a", "b", null), "is a directory");
    assertRefused(call("edit_file", "{\"path\":\"a.txt\",\"old_str\":\"two\"}"), "new_str");
  }

  @Test
  void editForgivesLineNumbersCopiedFromReadFile() throws IOException {
    String shown = call("read_file", "{\"path\":\"a.txt\",\"offset\":2,\"limit\":1}").content();
    String line = shown.substring(0, shown.indexOf('\n'));
    assertEquals("     2\ttwo", line);
    assertEquals(ToolResult.ok("edited a.txt: replaced 1 occurrence (first at line 2)"), edit("a.txt", line, "     2\tTWO", null));
    assertEquals("one\nTWO\nthree\n", Files.readString(workspace.resolve("a.txt")));
    edit("a.txt", "     1\tone\n     2\tTWO", "uno\ndos", null);
    assertEquals("uno\ndos\nthree\n", Files.readString(workspace.resolve("a.txt")), "a clean new_str is kept as is");
    assertEquals("x", FilesPlugin.withoutLineNumbers("  12\tx"));
    assertEquals(null, FilesPlugin.withoutLineNumbers("12\tx\nno prefix"));
  }

  @Test
  void editReplacesEveryOccurrenceWhenAsked() throws IOException {
    Files.writeString(workspace.resolve("dup.txt"), "x = 1\ny = 1\nx = 1\n");
    assertEquals(ToolResult.ok("edited dup.txt: replaced 2 occurrences (first at line 1)"), edit("dup.txt", "x = 1", "x = 2", true));
    assertEquals("x = 2\ny = 1\nx = 2\n", Files.readString(workspace.resolve("dup.txt")));
  }

  @Test
  void editKeepsWindowsLineEndings() throws IOException {
    Files.writeString(workspace.resolve("crlf.txt"), "a\r\nb\r\nc\r\n");
    assertTrue(!edit("crlf.txt", "a\nb\n", "a\nB\n", null).isError());
    assertEquals("a\r\nB\r\nc\r\n", Files.readString(workspace.resolve("crlf.txt")));
  }

  @Test
  void editStaysInsideTheWorkspaceAndNeverWritesThroughALink() throws IOException {
    assertRefused(edit("../secret.txt", "outside", "x", null), "outside the workspace");
    Files.createSymbolicLink(workspace.resolve("up"), dir);
    assertRefused(edit("up/secret.txt", "outside", "x", null), "outside the workspace");
    Files.createSymbolicLink(workspace.resolve("inner.txt"), workspace.resolve("a.txt"));
    assertRefused(edit("inner.txt", "two", "2", null), "symbolic link");
    assertEquals("outside\n", Files.readString(dir.resolve("secret.txt")));
    assertEquals("one\ntwo\nthree\n", Files.readString(workspace.resolve("a.txt")));
  }

  @Test
  void editIsLimitedLikeWrites() throws IOException {
    files.update(new FilesConfig(workspace.toString(), 0, 0, 16));
    assertRefused(edit("a.txt", "two", "a much longer line", null), "the most the file tools write is 16");
    assertEquals("one\ntwo\nthree\n", Files.readString(workspace.resolve("a.txt")));
  }

  @Test
  void editIsAskedLikeAnyWrite() throws IOException {
    root.dispose();
    Root asking = Context.root("asking");
    try {
      asking.plugin("files", new FilesPlugin(), new FilesConfig(workspace.toString(), 0, 0, 0));
      java.util.List<Tools.Approval> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
      asking.plugin("user", ctx -> {
        ctx.hook(Tools.APPROVE, a -> {
          asked.add(a);
          return Tools.Decision.DENY;
        });
        return null;
      });
      ToolResult r = Tools.call(asking, new ToolCall("id", "edit_file",
          "{\"path\":\"a.txt\",\"old_str\":\"two\",\"new_str\":\"2\"}"), new ToolContext("c", new CancelToken()));
      assertTrue(r.isError(), r.content());
      assertEquals(1, asked.size());
      assertEquals(Risk.WRITE, asked.get(0).risk());
      assertEquals("one\ntwo\nthree\n", Files.readString(workspace.resolve("a.txt")));
    } finally {
      asking.dispose();
    }
  }

  @Test
  void readsSayWhatTheyShowAndWritesChangeTheVersion() throws IOException {
    ToolResult first = raw("read_file", "{\"path\":\"a.txt\",\"offset\":2,\"limit\":1}");
    assertEquals("file:a.txt", first.resource().key());
    assertEquals(2, first.resource().from());
    assertEquals(2, first.resource().to());
    ToolResult whole = raw("read_file", "{\"path\":\"sub/../a.txt\"}");
    assertTrue(whole.resource().covers(first.resource()), "same file, same version, all its lines");
    assertEquals(null, whole.compact(), "too small to be worth an outline");

    ToolResult edited = raw("edit_file", "{\"path\":\"a.txt\",\"old_str\":\"two\",\"new_str\":\"2\"}");
    assertEquals("file:a.txt", edited.resource().key());
    assertTrue(!edited.resource().isView());
    ToolResult after = raw("read_file", "{\"path\":\"a.txt\"}");
    assertEquals(edited.resource().version(), after.resource().version(), "the edit reports what a read then sees");
    assertTrue(!after.resource().version().equals(whole.resource().version()));
    ToolResult written = raw("write_file", "{\"path\":\"a.txt\",\"content\":\"x\\n\"}");
    assertEquals(raw("read_file", "{\"path\":\"a.txt\"}").resource().version(), written.resource().version());
  }

  @Test
  void writesAndEditsShowTheUserWhatChanged() throws IOException {
    assertEquals("@@ -1,3 +1,3 @@\n one\n-two\n+2\n three\n",
        raw("edit_file", "{\"path\":\"a.txt\",\"old_str\":\"two\",\"new_str\":\"2\"}").diff());
    assertEquals("@@ -0,0 +1,2 @@\n+new\n+file\n",
        raw("write_file", "{\"path\":\"n.txt\",\"content\":\"new\\nfile\\n\"}").diff(), "a new file: all added");
    assertEquals("@@ -1,2 +1,1 @@\n-new\n-file\n+other\n",
        raw("write_file", "{\"path\":\"n.txt\",\"content\":\"other\\n\"}").diff(), "an overwrite: against the old text");
    assertEquals("@@ -1,1 +1,2 @@\n other\n+more\n",
        raw("write_file", "{\"path\":\"n.txt\",\"content\":\"more\\n\",\"append\":true}").diff());
    assertEquals(null, raw("write_file", "{\"path\":\"n.txt\",\"content\":\"other\\nmore\\n\"}").diff(),
        "the same text: nothing changed");
    Files.write(workspace.resolve("bin"), new byte[] {1, 0, 2});
    assertEquals(null, raw("write_file", "{\"path\":\"bin\",\"content\":\"text\"}").diff(), "was binary: not shown");
    assertEquals(null, raw("read_file", "{\"path\":\"a.txt\"}").diff(), "a read changes nothing");
  }

  @Test
  void longReadsCarryAnOutline() throws IOException {
    StringBuilder js = new StringBuilder("import React from 'react'\nimport { a } from './a'\n\n");
    js.append("export function Header({ title }) {\n");
    for (int i = 0; i < 60; i++) js.append("  const line").append(i).append(" = 'some text to make it long';\n");
    js.append("  return title\n}\n\nexport default Header\n");
    Files.writeString(workspace.resolve("Header.jsx"), js.toString());
    ToolResult r = raw("read_file", "{\"path\":\"Header.jsx\"}");
    String outline = r.compact();
    assertTrue(outline.startsWith("outline of Header.jsx, lines 1-68 of 68"), outline);
    assertTrue(outline.contains("     1\t[2 imports, lines 1-2]"), outline);
    assertTrue(outline.contains("     4\texport function Header({ title }) {"), outline);
    assertTrue(outline.contains("export default Header"), outline);
    assertTrue(!outline.contains("const line5 "), "indented bindings are not declarations: " + outline);
    assertTrue(outline.length() < r.content().length() / 4, outline.length() + " of " + r.content().length());
  }

  @Test
  void readsStopAtMaxLinesWithoutALimit() throws IOException {
    files.update(new FilesConfig(workspace.toString(), 0, 0, 0, 2));
    assertEquals(ToolResult.ok("     1\tone\n     2\ttwo\n… [1 more line; continue with offset=3]"),
        call("read_file", "{\"path\":\"a.txt\"}"));
  }

  @Test
  void globFindsFilesByNameOrPathAndSkipsWhatIsNotSource() throws IOException {
    Files.createDirectories(workspace.resolve("src/components"));
    Files.writeString(workspace.resolve("src/components/Header.jsx"), "x");
    Files.writeString(workspace.resolve("src/App.jsx"), "x");
    Files.createDirectories(workspace.resolve("node_modules/react"));
    Files.writeString(workspace.resolve("node_modules/react/index.jsx"), "x");
    Files.createDirectories(workspace.resolve("dist"));
    Files.writeString(workspace.resolve("dist/App.jsx"), "x");
    Files.writeString(workspace.resolve(".gitignore"), "dist\n");
    String names = call("glob", "{\"pattern\":\"*.jsx\"}").content();
    assertTrue(names.contains("src/components/Header.jsx\n") && names.contains("src/App.jsx\n"), names);
    assertTrue(!names.contains("node_modules") && !names.contains("dist/"), names);
    assertEquals("src/components/Header.jsx\n",
        call("glob", "{\"pattern\":\"components/*.jsx\",\"path\":\"src\"}").content());
    assertEquals("no files match *.py", call("glob", "{\"pattern\":\"*.py\"}").content());
    assertRefused(call("glob", "{\"pattern\":\"*\",\"path\":\"..\"}"), "outside the workspace");
  }

  @Test
  void grepFindsLinesGroupedByFile() throws IOException {
    Files.createDirectories(workspace.resolve("src"));
    Files.writeString(workspace.resolve("src/Section.jsx"), "a\nexport function formatPrice(value) {\n  return value\n}\n");
    Files.writeString(workspace.resolve("src/Products.jsx"), "import { formatPrice } from './Section'\n");
    Files.write(workspace.resolve("src/logo.bin"), new byte[] {'f', 'o', 'r', 'm', 'a', 't', 'P', 0});
    String out = call("grep", "{\"pattern\":\"formatPrice\"}").content();
    assertEquals("src/Products.jsx\n     1: import { formatPrice } from './Section'\n"
        + "src/Section.jsx\n     2: export function formatPrice(value) {\n", out);
    String context = call("grep",
        "{\"pattern\":\"FORMATPRICE\",\"ignore_case\":true,\"glob\":\"Section*\",\"context\":1}").content();
    assertEquals("src/Section.jsx\n     1- a\n     2: export function formatPrice(value) {\n     3-   return value\n",
        context);
    assertEquals("src/Products.jsx (1 match)\nsrc/Section.jsx (1 match)\n",
        call("grep", "{\"pattern\":\"formatPrice\",\"files_only\":true}").content());
    assertEquals("no matches for nothing-here", call("grep", "{\"pattern\":\"nothing-here\"}").content());
    assertRefused(call("grep", "{\"pattern\":\"(\"}"), "bad regular expression");
  }

  @Test
  void grepSaysHowManyMatchesItLeftOut() throws IOException {
    files.update(new FilesConfig(workspace.toString(), 0, 2, 0));
    Files.writeString(workspace.resolve("many.txt"), "hit\nhit\nhit\nhit\n");
    String out = call("grep", "{\"pattern\":\"hit\",\"path\":\"many.txt\"}").content();
    assertTrue(out.startsWith("many.txt\n     1: hit\n     2: hit\n"), out);
    assertTrue(out.endsWith("… 4 matches in 1 files in all; narrow the pattern, path or glob\n"), out);
  }
}

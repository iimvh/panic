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

  private ToolResult call(String name, String args) {
    return Tools.call(root, new ToolCall("id", name, args), new ToolContext("c", new CancelToken()));
  }

  private void assertRefused(ToolResult r, String why) {
    assertTrue(r.isError(), r.content());
    assertTrue(r.content().contains(why), r.content());
  }

  @Test
  void readsFilesWithOffsetAndLimit() {
    assertEquals(ToolResult.ok("one\ntwo\nthree\n"), call("read_file", "{\"path\":\"a.txt\"}"));
    assertEquals(ToolResult.ok("inside\n"), call("read_file", "{\"path\":\"sub/../sub/b.txt\"}"));
    assertEquals(ToolResult.ok("two\n… [more lines; continue with offset=3]"),
        call("read_file", "{\"path\":\"a.txt\",\"offset\":2,\"limit\":1}"));
    assertRefused(call("read_file", "{\"path\":\"a.txt\",\"offset\":9}"), "has only 3 lines");
    assertRefused(call("read_file", "{\"path\":\"nope.txt\"}"), "not found: nope.txt");
    assertRefused(call("read_file", "{\"path\":\"sub\"}"), "is a directory");
  }

  @Test
  void cutsLongFiles() throws IOException {
    files.update(new FilesConfig(workspace.toString(), 10, 0, 0));
    assertEquals(ToolResult.ok("one\ntwo\n… [truncated at 10 chars; continue with offset=3]"),
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
    assertEquals(ToolResult.ok("outside\n"), call("read_file", "{\"path\":\"secret.txt\"}"));
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
}

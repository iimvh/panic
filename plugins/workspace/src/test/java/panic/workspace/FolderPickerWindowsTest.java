package panic.workspace;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import core.CancelledException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import panic.util.CancelToken;

/**
 * The real folder dialog of Windows, not a fake: a dialog nobody can see is the bug this guards against. Skipped
 * elsewhere, and where there is no interactive desktop (CI runs under a service account with no window station).
 */
class FolderPickerWindowsTest {

  /** Lists the visible windows titled "Browse For Folder" of the process {@code pid}'s subtree: one line each. */
  private static final String FIND = String.join("\n",
      "Add-Type -TypeDefinition @'",
      "using System; using System.Text; using System.Collections.Generic; using System.Runtime.InteropServices;",
      "public class W {",
      "  public delegate bool P(IntPtr h, IntPtr l);",
      "  [DllImport(\"user32.dll\")] public static extern bool EnumWindows(P p, IntPtr l);",
      "  [DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);",
      "  [DllImport(\"user32.dll\")] public static extern bool IsWindowVisible(IntPtr h);",
      "  [DllImport(\"user32.dll\", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);",
      "  public static List<uint> Find() { var r = new List<uint>();",
      "    EnumWindows((h, l) => { var t = new StringBuilder(256); GetWindowText(h, t, 256);",
      "      if (t.ToString() == \"Browse For Folder\" && IsWindowVisible(h)) { uint p; GetWindowThreadProcessId(h, out p); r.Add(p); }",
      "      return true; }, IntPtr.Zero); return r; } }",
      "'@",
      "[W]::Find() | ForEach-Object { $_ }");

  private static List<Long> visibleDialogOwners() throws Exception {
    // -EncodedCommand: the script's quotes would not survive the Windows command line
    String encoded = java.util.Base64.getEncoder().encodeToString(FIND.getBytes(StandardCharsets.UTF_16LE));
    Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
        .redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor(30, TimeUnit.SECONDS);
    List<Long> pids = new ArrayList<>();
    for (String line : out.split("\\R")) {
      if (line.strip().matches("\\d+")) pids.add(Long.parseLong(line.strip()));
    }
    return pids;
  }

  private static boolean alive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  @Test
  void theRealDialogIsVisibleAndCancelClosesIt() throws Exception {
    assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    assumeTrue(System.getenv("CI") == null, "needs an interactive desktop");

    CancelToken cancel = new CancelToken();
    List<String> warnings = new ArrayList<>();
    CompletableFuture<Object> dialog = CompletableFuture.supplyAsync(() -> {
      try {
        return FolderPickerPlugin.systemDialog(cancel, 60, warnings::add);
      } catch (CancelledException e) {
        return e;
      } catch (java.io.IOException e) {
        return e;
      }
    });

    // it has to show up, and be seen by the window manager as a visible window of the process we started
    List<Long> owners = List.of();
    for (int i = 0; i < 20 && owners.isEmpty(); i++) {
      Thread.sleep(500);
      assumeTrue(!dialog.isDone(), "the dialog could not start here: " + dialog.getNow(null));
      owners = visibleDialogOwners();
    }
    assertTrue(!owners.isEmpty(), "no visible \"Browse For Folder\" window appeared within 10s");
    long owner = owners.get(0);
    assertTrue(ProcessHandle.current().descendants().anyMatch(h -> h.pid() == owner),
        "the window belongs to the process Panic started");

    cancel.cancel();
    Object result = dialog.get(15, TimeUnit.SECONDS);
    assertTrue(result instanceof CancelledException, "cancelling ends the call: " + result);
    for (int i = 0; i < 20 && alive(owner); i++) Thread.sleep(250);
    assertTrue(!alive(owner), "the dialog process is gone");
    assertTrue(warnings.isEmpty(), warnings.toString());
  }
}

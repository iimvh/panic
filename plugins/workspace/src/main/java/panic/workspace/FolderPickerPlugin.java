package panic.workspace;

import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import core.Signal;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import panic.tools.Workspace;

/**
 * Answers {@link Workspace#PICK} by opening the operating system's folder dialog on the machine Panic runs on:
 * a browser cannot hand a page the absolute path of a folder, but the server runs next to the user. Windows uses
 * a PowerShell {@code FolderBrowserDialog}, macOS {@code osascript}, other systems {@code zenity} or
 * {@code kdialog}. The handler blocks the caller (the command that asked) until the dialog closes, the
 * {@link FolderPickerConfig#timeoutSeconds} pass or the caller's signal is cancelled, and then closes the dialog,
 * so nothing outlives the request. It answers null when the user cancels, the timeout passes or no dialog can be
 * opened, and says why to its {@code warn} callback.
 *
 * Requires nothing; with {@code enabled} false it answers nothing. A config change re-registers the hook.
 */
public final class FolderPickerPlugin implements ConfigPlugin<FolderPickerConfig> {

  /** Shows a dialog and returns the chosen folder's text, or null when cancelled. */
  @FunctionalInterface
  interface Dialog {
    String choose(Signal signal, int timeoutSeconds) throws IOException;
  }

  private final Dialog dialog;
  private final Consumer<String> warn;

  /** {@code warn} hears why no dialog could be shown or why it gave no folder (the server's log, in practice). */
  public FolderPickerPlugin(Consumer<String> warn) {
    this.warn = warn;
    this.dialog = (signal, timeoutSeconds) -> systemDialog(signal, timeoutSeconds, warn);
  }

  /** For tests: a fake dialog. */
  FolderPickerPlugin(Dialog dialog) {
    this.warn = text -> { };
    this.dialog = dialog;
  }

  @Override
  public Disposable apply(Context ctx, FolderPickerConfig cfg) {
    if (!cfg.enabled()) return null;
    ctx.hook(Workspace.PICK, q -> {
      String text;
      try {
        text = dialog.choose(q.signal(), cfg.timeoutSeconds());
      } catch (IOException e) {
        warn.accept("[folder-picker] no folder dialog: " + e.getMessage());
        return null; // the caller asks for a typed path
      }
      if (text == null || text.isBlank()) return null;
      try {
        return new Workspace.Picked(Path.of(text.strip()).toAbsolutePath().normalize());
      } catch (InvalidPathException e) {
        return null;
      }
    });
    return null;
  }

  static String systemDialog(Signal signal, int timeoutSeconds, Consumer<String> warn) throws IOException {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    List<List<String>> candidates;
    if (os.contains("win")) {
      candidates = List.of(List.of("powershell", "-NoProfile", "-NonInteractive", "-STA", "-Command", WINDOWS_SCRIPT));
    } else if (os.contains("mac")) {
      candidates = List.of(List.of("osascript", "-e", "POSIX path of (choose folder with prompt \"Choose a project folder\")"));
    } else {
      candidates = List.of(
          List.of("zenity", "--file-selection", "--directory", "--title=Choose a project folder"),
          List.of("kdialog", "--getexistingdirectory", System.getProperty("user.home", "/")));
    }
    IOException last = new IOException("no folder dialog available");
    for (List<String> command : candidates) {
      try {
        return run(command, signal, timeoutSeconds, warn);
      } catch (IOException e) {
        last = e; // program missing: try the next one
      }
    }
    throw last;
  }

  /**
   * The dialog hangs off a hidden top-most form that is really shown and activated: with no shown owner,
   * Windows may leave the dialog behind the browser, where nobody sees it. The form is 1x1 and transparent, so it
   * never covers (or blocks clicks on) anything while the dialog is open.
   */
  static final String WINDOWS_SCRIPT = String.join("; ",
      "Add-Type -AssemblyName System.Windows.Forms",
      "$owner = New-Object System.Windows.Forms.Form",
      "$owner.Text = 'Panic'",
      "$owner.TopMost = $true",
      "$owner.ShowInTaskbar = $false",
      "$owner.Opacity = 0",
      "$owner.FormBorderStyle = 'None'",
      "$owner.Size = New-Object System.Drawing.Size(1, 1)",
      "$owner.StartPosition = 'CenterScreen'",
      "$owner.Show()",
      "$owner.Activate()",
      "$d = New-Object System.Windows.Forms.FolderBrowserDialog",
      "$d.Description = 'Choose a project folder'",
      "$r = $d.ShowDialog($owner)",
      "$owner.Dispose()",
      "if ($r -eq 'OK') { [Console]::Out.Write($d.SelectedPath) }");

  /**
   * Output and errors go to temporary files, so waiting can honour the timeout and the signal (like
   * {@code checks}). A program that is missing is an IOException; one that fails with a message is too (the
   * message tells why there was no dialog); a cancel (exit 1, no message) or a timeout is null.
   */
  private static String run(List<String> command, Signal signal, int timeoutSeconds, Consumer<String> warn)
      throws IOException {
    Path out = Files.createTempFile("panic-folder-", ".txt");
    Path err = Files.createTempFile("panic-folder-", ".err");
    try {
      Process p = new ProcessBuilder(command).redirectError(err.toFile()).redirectOutput(out.toFile())
          .redirectInput(ProcessBuilder.Redirect.from(new File(File.separatorChar == '\\' ? "NUL" : "/dev/null")))
          .start();
      Disposable onCancel = signal.onCancel(() -> kill(p));
      boolean finished;
      try {
        finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        finished = false;
      } finally {
        onCancel.dispose();
      }
      if (!finished) kill(p);
      signal.throwIfCancelled();
      if (!finished) {
        warn.accept("[folder-picker] the folder dialog was not closed within " + timeoutSeconds + "s; closed it");
        return null;
      }
      if (p.exitValue() != 0) {
        String why = Files.readString(err, StandardCharsets.UTF_8).strip();
        if (!why.isEmpty()) throw new IOException(why.lines().findFirst().orElse(why));
        return null;
      }
      String text = Files.readString(out, StandardCharsets.UTF_8).strip();
      return text.isEmpty() ? null : text;
    } finally {
      for (Path f : List.of(out, err)) {
        try {
          Files.deleteIfExists(f);
        } catch (IOException ignored) {
          // a temporary file left behind is harmless
        }
      }
    }
  }

  private static void kill(Process p) {
    p.descendants().forEach(ProcessHandle::destroyForcibly);
    p.destroyForcibly();
  }
}

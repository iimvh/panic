package panic.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Gives back the command-line arguments that Windows mangled before {@code main} ran.
 *
 * {@code java.exe} gets its arguments from the C runtime in the ANSI code page ({@code sun.jnu.encoding}, e.g.
 * Cp1252), so every character outside it ({@code ế}, {@code đ}, an emoji) is already {@code ?} in {@code args}:
 * a Vietnamese prompt given as an argument reached the model as {@code vi?t ti?ng Vi?t}. The UTF-16 command line
 * the process was started with is intact, but JDK 21 does not expose it ({@code ProcessHandle.Info.commandLine()} is
 * empty on Windows, and calling {@code GetCommandLineW} needs the FFM API, still a preview there). So it is read
 * from WMI through PowerShell, which takes about 0.4 s and happens only when an argument holds a {@code ?}.
 *
 * The arguments are the last tokens of that command line (the launcher ends with {@code panic.app.Main %*}). They
 * replace {@code args} only when each one, put through the code page the way Windows does (one {@code ?} per UTF-16
 * unit it cannot encode), gives exactly the mangled argument; otherwise (a launcher that expanded a wildcard or an
 * @argfile, a failed query) {@code args} stay as they are.
 */
final class WindowsArgs {

  private WindowsArgs() {
  }

  /** {@code args} with the characters the ANSI code page lost put back, where they can be found. */
  static String[] recover(String[] args) {
    if (File.separatorChar != '\\' || !mangled(args)) return args;
    Charset ansi;
    try {
      ansi = Charset.forName(System.getProperty("sun.jnu.encoding", "Cp1252"));
    } catch (RuntimeException e) {
      return args;
    }
    String commandLine = commandLine(ProcessHandle.current().pid());
    return commandLine == null ? args : match(args, split(commandLine), ansi);
  }

  private static boolean mangled(String[] args) {
    for (String a : args) {
      if (a.indexOf('?') >= 0) return true;
    }
    return false;
  }

  /** The tail of {@code tokens} if it is what Windows turned into {@code args}, else {@code args}. */
  static String[] match(String[] args, List<String> tokens, Charset ansi) {
    if (tokens.size() < args.length) return args;
    CharsetEncoder encoder = ansi.newEncoder();
    String[] out = new String[args.length];
    int first = tokens.size() - args.length;
    for (int i = 0; i < args.length; i++) {
      String token = tokens.get(first + i);
      if (!asAnsi(token, encoder).equals(args[i])) return args;
      out[i] = token;
    }
    return out;
  }

  /** {@code s} as the C runtime decodes it: each UTF-16 unit the code page cannot hold becomes {@code ?}. */
  private static String asAnsi(String s, CharsetEncoder encoder) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      b.append(encoder.canEncode(c) ? c : '?');
    }
    return b.toString();
  }

  /**
   * A Windows command line split into arguments by the rules of the Microsoft C runtime: spaces and tabs separate
   * outside quotes; 2n backslashes before a quote give n backslashes and the quote opens or closes; 2n+1 give n and a
   * literal quote; {@code ""} inside quotes is a literal quote; other backslashes are literal.
   */
  static List<String> split(String commandLine) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inToken = false;
    boolean quoted = false;
    int n = commandLine.length();
    for (int i = 0; i < n; i++) {
      char c = commandLine.charAt(i);
      if (c == '\\') {
        int run = 0;
        while (i < n && commandLine.charAt(i) == '\\') {
          run++;
          i++;
        }
        if (i < n && commandLine.charAt(i) == '"') {
          cur.append("\\".repeat(run / 2));
          if (run % 2 == 1) {
            cur.append('"');
          } else {
            quoted = !quoted;
          }
        } else {
          cur.append("\\".repeat(run));
          i--;
        }
        inToken = true;
      } else if (c == '"') {
        if (quoted && i + 1 < n && commandLine.charAt(i + 1) == '"') {
          cur.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
        inToken = true;
      } else if ((c == ' ' || c == '\t') && !quoted) {
        if (inToken) out.add(cur.toString());
        cur.setLength(0);
        inToken = false;
      } else {
        cur.append(c);
        inToken = true;
      }
    }
    if (inToken) out.add(cur.toString());
    return out;
  }

  /** The UTF-16 command line of process {@code pid}, from WMI; null when it cannot be read. */
  private static String commandLine(long pid) {
    String script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
        + "(Get-CimInstance Win32_Process -Filter 'ProcessId=" + pid + "').CommandLine";
    Process p = null;
    Path out = null;
    try {
      // output to a file, not a pipe: reading a pipe would block past the time limit if PowerShell hangs
      out = Files.createTempFile("panic-args-", ".txt");
      p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
          .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(out.toFile())
          .redirectInput(ProcessBuilder.Redirect.from(new File("NUL"))).start();
      if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
      String line = Files.readString(out, StandardCharsets.UTF_8).strip();
      if (line.startsWith("﻿")) line = line.substring(1);
      return line.isEmpty() ? null : line;
    } catch (IOException | RuntimeException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } finally {
      if (p != null) p.destroyForcibly();
      if (out != null) {
        try {
          Files.deleteIfExists(out);
        } catch (IOException ignored) {
          // a temporary file left behind is harmless
        }
      }
    }
  }
}

package panic.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * A line diff of two texts as unified-diff hunks ({@code @@ -a,b +c,d @@}, then lines starting with {@code ' '},
 * {@code '+'} or {@code '-'}), so the user can see what a file tool changed. Hand-written (Myers, after trimming the
 * common start and end) because the Java side takes no library beyond z and Jackson.
 *
 * <p>Bounded: past {@link #MAX_EDITS} differences the changed middle is shown as removed, then added, and past
 * {@link #MAX_LINES} lines the rest is replaced by one {@code "\ not shown: +A -R"} line (the added and removed lines left out).
 */
final class Diff {

  static final int CONTEXT = 3;
  static final int MAX_LINES = 500;
  static final int MAX_EDITS = 1000;

  private Diff() {}

  private record Op(char kind, int a, int b) {} // a, b: 0-based line in before, after (the next one when absent)

  /** The hunks that turn {@code before} into {@code after}, or null when no line changed. */
  static String unified(String before, String after) {
    String[] a = lines(before);
    String[] b = lines(after);
    int start = 0;
    while (start < a.length && start < b.length && a[start].equals(b[start])) start++;
    int endA = a.length;
    int endB = b.length;
    while (endA > start && endB > start && a[endA - 1].equals(b[endB - 1])) {
      endA--;
      endB--;
    }
    if (start == endA && start == endB) return null;
    List<Op> ops = new ArrayList<>(a.length + b.length);
    for (int i = 0; i < start; i++) ops.add(new Op(' ', i, i));
    middle(a, b, start, endA, start, endB, ops);
    for (int i = endA, j = endB; i < a.length; i++, j++) ops.add(new Op(' ', i, j));
    return hunks(ops, a, b);
  }

  private static String[] lines(String text) {
    if (text.isEmpty()) return new String[0];
    String[] split = text.split("\n", -1);
    int n = text.endsWith("\n") ? split.length - 1 : split.length;
    String[] out = new String[n];
    System.arraycopy(split, 0, out, 0, n);
    return out;
  }

  /** The edit script of a[a0, a1) into b[b0, b1) (Myers, O((N+M)D)), or all removed then all added past MAX_EDITS. */
  private static void middle(String[] a, String[] b, int a0, int a1, int b0, int b1, List<Op> ops) {
    int n = a1 - a0;
    int m = b1 - b0;
    int max = Math.min(n + m, MAX_EDITS);
    List<int[]> trace = new ArrayList<>();
    int[] v = new int[3]; // v[k + d + 1]: furthest x on diagonal k after d edits, for k in [-d-1, d+1]
    int found = -1;
    for (int d = 0; d <= max && found < 0; d++) {
      int[] next = new int[2 * d + 3];
      for (int k = -d; k <= d; k += 2) {
        int x = k == -d || (k != d && at(v, d - 1, k - 1) < at(v, d - 1, k + 1))
            ? at(v, d - 1, k + 1) : at(v, d - 1, k - 1) + 1;
        int y = x - k;
        while (x < n && y < m && a[a0 + x].equals(b[b0 + y])) {
          x++;
          y++;
        }
        next[k + d + 1] = x;
        if (x >= n && y >= m) {
          found = d;
          break;
        }
      }
      trace.add(next);
      v = next;
    }
    if (found < 0) {
      for (int i = a0; i < a1; i++) ops.add(new Op('-', i, b0));
      for (int j = b0; j < b1; j++) ops.add(new Op('+', a1, j));
      return;
    }
    List<Op> back = new ArrayList<>();
    int x = n;
    int y = m;
    for (int d = found; d > 0; d--) {
      int[] prev = trace.get(d - 1);
      int k = x - y;
      int pk = k == -d || (k != d && at(prev, d - 1, k - 1) < at(prev, d - 1, k + 1)) ? k + 1 : k - 1;
      int px = at(prev, d - 1, pk);
      int py = px - pk;
      while (x > px && y > py) { // the snake after the edit
        x--;
        y--;
        back.add(new Op(' ', a0 + x, b0 + y));
      }
      if (pk == k + 1) { // down: b[py] inserted
        back.add(new Op('+', a0 + px, b0 + py));
      } else { // right: a[px] removed
        back.add(new Op('-', a0 + px, b0 + py));
      }
      x = px;
      y = py;
    }
    while (x > 0 && y > 0) {
      x--;
      y--;
      back.add(new Op(' ', a0 + x, b0 + y));
    }
    for (int i = back.size() - 1; i >= 0; i--) ops.add(back.get(i));
  }

  /** v[k] of the row for {@code d} edits (k in [-d-1, d+1]); 0 before the first row. */
  private static int at(int[] v, int d, int k) {
    if (d < 0) return 0;
    int i = k + d + 1;
    return i >= 0 && i < v.length ? v[i] : 0;
  }

  private static String hunks(List<Op> ops, String[] a, String[] b) {
    StringBuilder out = new StringBuilder();
    int written = 0;
    int i = 0;
    while (i < ops.size()) {
      while (i < ops.size() && ops.get(i).kind() == ' ') i++;
      if (i == ops.size()) break;
      int from = Math.max(0, i - CONTEXT);
      int to = i; // one past the last change of this hunk
      int j = i;
      while (j < ops.size()) {
        if (ops.get(j).kind() != ' ') {
          to = j + 1;
          j++;
        } else if (j - to > 2 * CONTEXT) { // hunks whose context would touch are one
          break;
        } else {
          j++;
        }
      }
      int end = Math.min(ops.size(), to + CONTEXT);
      if (written + 2 > MAX_LINES) return out.append(more(ops, from)).toString();
      int oldCount = 0;
      int newCount = 0;
      for (int r = from; r < end; r++) {
        char kind = ops.get(r).kind();
        if (kind != '+') oldCount++;
        if (kind != '-') newCount++;
      }
      Op first = ops.get(from);
      int oldStart = oldCount == 0 ? first.a() : first.a() + 1;
      int newStart = newCount == 0 ? first.b() : first.b() + 1;
      out.append("@@ -").append(oldStart).append(',').append(oldCount)
          .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
      written++;
      for (int r = from; r < end; r++) {
        if (written + 1 >= MAX_LINES) return out.append(more(ops, r)).toString();
        written++;
        Op op = ops.get(r);
        String text = op.kind() == '+' ? b[op.b()] : a[op.a()];
        if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
        out.append(op.kind()).append(text).append('\n');
      }
      i = end;
    }
    return out.toString();
  }

  /** The note that ends a cut diff: the added and removed lines from {@code ops[from]} on that it leaves out. */
  private static String more(List<Op> ops, int from) {
    int added = 0;
    int removed = 0;
    for (int r = from; r < ops.size(); r++) {
      if (ops.get(r).kind() == '+') added++;
      if (ops.get(r).kind() == '-') removed++;
    }
    return "\\ not shown: +" + added + " -" + removed + "\n";
  }
}

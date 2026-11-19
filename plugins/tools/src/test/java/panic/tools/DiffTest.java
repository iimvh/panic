package panic.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiffTest {

  private static String numbered(int from, int to) {
    StringBuilder out = new StringBuilder();
    for (int i = from; i <= to; i++) out.append("line ").append(i).append('\n');
    return out.toString();
  }

  @Test
  void nothingChanged() {
    assertNull(Diff.unified("", ""));
    assertNull(Diff.unified("a\nb\n", "a\nb\n"));
  }

  @Test
  void newAndEmptiedFiles() {
    assertEquals("@@ -0,0 +1,2 @@\n+a\n+b\n", Diff.unified("", "a\nb\n"));
    assertEquals("@@ -1,2 +0,0 @@\n-a\n-b\n", Diff.unified("a\nb", ""));
  }

  @Test
  void aChangeInTheMiddleKeepsThreeLinesAround() {
    String before = numbered(1, 10);
    String after = before.replace("line 5\n", "five\n");
    assertEquals("@@ -2,7 +2,7 @@\n line 2\n line 3\n line 4\n-line 5\n+five\n line 6\n line 7\n line 8\n",
        Diff.unified(before, after));
  }

  @Test
  void insertsAndDeletesCountTheirLines() {
    assertEquals("@@ -1,2 +1,3 @@\n a\n+x\n b\n", Diff.unified("a\nb\n", "a\nx\nb\n"));
    assertEquals("@@ -1,3 +1,2 @@\n a\n-x\n b\n", Diff.unified("a\nx\nb\n", "a\nb\n"));
  }

  @Test
  void farApartChangesAreSeparateHunks() {
    String before = numbered(1, 30);
    String after = before.replace("line 3\n", "three\n").replace("line 25\n", "");
    assertEquals("@@ -1,6 +1,6 @@\n line 1\n line 2\n-line 3\n+three\n line 4\n line 5\n line 6\n"
        + "@@ -22,7 +22,6 @@\n line 22\n line 23\n line 24\n-line 25\n line 26\n line 27\n line 28\n",
        Diff.unified(before, after));
  }

  @Test
  void closeChangesShareAHunk() {
    String before = numbered(1, 20);
    String after = before.replace("line 5\n", "five\n").replace("line 11\n", "eleven\n");
    String diff = Diff.unified(before, after);
    assertEquals(1, diff.split("@@ -", -1).length - 1, diff);
  }

  @Test
  void interleavedChangesFindTheCommonLines() {
    assertEquals("@@ -1,5 +1,5 @@\n-a\n+A\n b\n-c\n+C\n d\n e\n",
        Diff.unified("a\nb\nc\nd\ne\n", "A\nb\nC\nd\ne\n"));
  }

  @Test
  void carriageReturnsAreNotShown() {
    assertEquals("@@ -1,2 +1,2 @@\n a\n-b\n+c\n", Diff.unified("a\r\nb\r\n", "a\r\nc\r\n"));
  }

  @Test
  void aLongDiffIsCut() {
    String diff = Diff.unified("", numbered(1, 800));
    String[] lines = diff.split("\n");
    assertEquals(Diff.MAX_LINES, lines.length);
    assertEquals("\\ not shown: +302 -0", lines[lines.length - 1]);
  }

  @Test
  void tooManyDifferencesFallBackToRemovedThenAdded() {
    StringBuilder a = new StringBuilder();
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 1200; i++) {
      a.append("a").append(i).append('\n');
      b.append("b").append(i).append('\n');
    }
    String diff = Diff.unified(a.toString(), b.toString());
    assertTrue(diff.startsWith("@@ -1,1200 +1,1200 @@\n-a0\n-a1\n"), diff.substring(0, 60));
  }
}

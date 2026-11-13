package panic.workspace;

/**
 * {@code dir}: where the projects and the chat-to-project map are stored (one {@code projects.json}), relative
 * to the app's working directory (null or blank = {@code .panic/workspace}).
 */
public record WorkspaceConfig(String dir) {

  public static final WorkspaceConfig DEFAULT = new WorkspaceConfig(null);

  public WorkspaceConfig {
    dir = dir == null || dir.isBlank() ? ".panic/workspace" : dir.strip();
  }
}

package panic.workspace;

/**
 * {@code enabled}: whether the plugin may open the operating system's folder dialog (turn it off when Panic runs
 * on a machine the user does not sit at; front-ends then ask for a typed path). {@code timeoutSeconds}: how long
 * the dialog may stay open before it is closed (non-positive = 120).
 */
public record FolderPickerConfig(boolean enabled, int timeoutSeconds) {

  public static final FolderPickerConfig DEFAULT = new FolderPickerConfig(true, 0);

  public FolderPickerConfig {
    if (timeoutSeconds <= 0) timeoutSeconds = 120;
  }
}

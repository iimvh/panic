package panic.tools;

/**
 * {@code root}: the directory the file tools may see (relative to the app's working directory); nothing outside
 * it is readable or writable, symlinks included. {@code maxBytes}: the most text {@code read_file} returns in
 * one call. {@code maxEntries}: the most entries {@code list_dir} shows, and the most matches {@code grep} and
 * paths {@code glob} return. {@code maxWriteBytes}: the most bytes {@code write_file} writes in one call.
 * {@code maxLines}: the lines {@code read_file} returns when the model gives no {@code limit} (a long file is read
 * in parts, after a search says which). Missing or non-positive values take the default.
 */
public record FilesConfig(String root, int maxBytes, int maxEntries, int maxWriteBytes, int maxLines) {

  public static final FilesConfig DEFAULT = new FilesConfig(".", 64 * 1024, 200, 1024 * 1024, 2_000);

  public FilesConfig {
    root = root == null || root.isBlank() ? "." : root.strip();
    if (maxBytes <= 0) maxBytes = 64 * 1024;
    if (maxEntries <= 0) maxEntries = 200;
    if (maxWriteBytes <= 0) maxWriteBytes = 1024 * 1024;
    if (maxLines <= 0) maxLines = 2_000;
  }

  public FilesConfig(String root, int maxBytes, int maxEntries, int maxWriteBytes) {
    this(root, maxBytes, maxEntries, maxWriteBytes, 0);
  }
}

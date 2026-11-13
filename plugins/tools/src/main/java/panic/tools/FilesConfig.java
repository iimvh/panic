package panic.tools;

/**
 * {@code root}: the directory the file tools may see (relative to the app's working directory); nothing outside
 * it is readable or writable, symlinks included. {@code maxBytes}: the most text {@code read_file} returns in
 * one call. {@code maxEntries}: the most entries {@code list_dir} shows. {@code maxWriteBytes}: the most bytes
 * {@code write_file} writes in one call. Missing or non-positive values take the default.
 */
public record FilesConfig(String root, int maxBytes, int maxEntries, int maxWriteBytes) {

  public static final FilesConfig DEFAULT = new FilesConfig(".", 64 * 1024, 200, 1024 * 1024);

  public FilesConfig {
    root = root == null || root.isBlank() ? "." : root.strip();
    if (maxBytes <= 0) maxBytes = 64 * 1024;
    if (maxEntries <= 0) maxEntries = 200;
    if (maxWriteBytes <= 0) maxWriteBytes = 1024 * 1024;
  }
}

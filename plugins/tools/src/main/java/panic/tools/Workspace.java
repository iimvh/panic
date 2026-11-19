package panic.tools;

import context.HookKey;
import core.Signal;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The public keys of workspaces ("projects"): the folder a conversation's file tools and checks work in. Plugins
 * ask these hooks instead of requiring the plugin that answers them ({@code workspace}, {@code folder-picker}):
 * with no answer there is no project, and callers fall back to their own configured directory.
 */
public final class Workspace {

  /** Which conversation's workspace to resolve. Sub-agents must ask with {@link ToolContext#origin()}. */
  public record Query(String conversationId) {
    public Query {
      Objects.requireNonNull(conversationId, "conversationId");
    }
  }

  /** A project folder and the conversations that work in it. {@code path} is absolute and normalized. */
  public record Project(Path path, String name, List<String> conversations) {
    public Project {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(name, "name");
      conversations = conversations == null ? List.of() : List.copyOf(conversations);
    }
  }

  /** Asks for the projects. */
  public enum Listing { ALL }

  /**
   * The active projects, and the conversations of removed projects ({@code hidden}): a front-end lists them
   * under no project at all, so removing a project hides its chats instead of deleting them.
   */
  public record Projects(List<Project> projects, List<String> hidden) {
    public Projects {
      projects = projects == null ? List.of() : List.copyOf(projects);
      hidden = hidden == null ? List.of() : List.copyOf(hidden);
    }
  }

  /** Asks the user for a folder; {@code signal} cancels the dialog. */
  public record PickQuery(Signal signal) {
    public PickQuery {
      Objects.requireNonNull(signal, "signal");
    }
  }

  /** A folder the user chose, as an absolute path. */
  public record Picked(Path path) {
    public Picked {
      Objects.requireNonNull(path, "path");
    }
  }

  /** A folder to show in the file manager of the machine Panic runs on. */
  public record RevealQuery(Path path) {
    public RevealQuery {
      Objects.requireNonNull(path, "path");
    }
  }

  /** The root of a conversation's workspace; answered by plugin {@code workspace}, null when it has no project. */
  public static final HookKey<Query, Path> ROOT = HookKey.of("workspace.root", Query.class, Path.class);

  /** The projects; answered by plugin {@code workspace}. */
  public static final HookKey<Listing, Projects> LIST = HookKey.of("workspace.list", Listing.class, Projects.class);

  /** A folder chosen in a native dialog; answered by plugin {@code folder-picker}, null when cancelled or unavailable. */
  public static final HookKey<PickQuery, Picked> PICK = HookKey.of("workspace.pick", PickQuery.class, Picked.class);

  /**
   * Opens a folder in the system's file manager (a browser cannot); answered by plugin {@code folder-picker}, TRUE
   * once started, null when unavailable (turned off, or no file manager). Callers check that the folder is one the
   * user chose (a project): handlers open whatever they are given.
   */
  public static final HookKey<RevealQuery, Boolean> REVEAL = HookKey.of("workspace.reveal", RevealQuery.class,
      Boolean.class);

  /**
   * The root {@code conversationId} works in: its project's folder, or {@code fallback} when it has none. Blocks
   * only as long as the handlers do (a lookup in memory).
   */
  public static Path root(context.Context ctx, String conversationId, Path fallback) {
    return panic.command.Hooks.bail(ctx, ROOT, new Query(conversationId)).orElse(fallback);
  }

  private Workspace() {
  }
}

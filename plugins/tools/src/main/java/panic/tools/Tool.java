package panic.tools;

import com.fasterxml.jackson.databind.JsonNode;
import panic.llm.ToolSpec;

/**
 * Something the model can call. Declared by a plugin with {@link Tools#register}, so it exists exactly as
 * long as that plugin.
 *
 * {@link #call} receives arguments already checked against {@code spec().parameters()}. It reports failures
 * the model should see (file not found, bad path) as {@link ToolResult#error}; an exception is turned into
 * one too. Long work must honour {@code ctx.signal()}: it is cancelled when the caller gives up or the
 * tool's plugin stops, and the plugin's disposal waits for the call to return.
 *
 * A call reaches {@link #call} only when the permission policy allows it for {@link #risk} (see
 * {@link Tools#POLICY} and {@link Tools#APPROVE}); the tool itself needs no permission check.
 */
public interface Tool {

  ToolSpec spec();

  /** What the tool can do; the permission policy decides from it whether a call runs or is asked first. */
  Risk risk();

  ToolResult call(JsonNode args, ToolContext ctx);
}

package panic.tools;

import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import context.Plugin;
import core.Disposable;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import panic.util.Json;
import panic.llm.ToolSpec;

/** Tool {@code time}: the current date and time, since models do not know it. Requires nothing. */
public final class ClockPlugin implements Plugin {

  @Override
  public Disposable apply(Context ctx) {
    Tools.register(ctx, new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec("time", "The current date and time (ISO-8601) and day of the week.",
            Json.parseOrNull("""
                {"type":"object","properties":{
                  "timezone":{"type":"string","description":"IANA zone such as Asia/Ho_Chi_Minh (default: the server's)"}},
                 "additionalProperties":false}"""));
      }

      @Override
      public Risk risk() {
        return Risk.READ;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext tc) {
        ZoneId zone;
        try {
          zone = args.hasNonNull("timezone") ? ZoneId.of(args.get("timezone").asText()) : ZoneId.systemDefault();
        } catch (DateTimeException e) {
          return ToolResult.error("unknown timezone: " + args.get("timezone").asText());
        }
        ZonedDateTime now = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
        return ToolResult.ok(now.format(DateTimeFormatter.ISO_ZONED_DATE_TIME) + " ("
            + now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ")");
      }
    });
    return null;
  }
}

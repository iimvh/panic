package panic.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks a value against the JSON Schema subset tools use: {@code type} (one or an array), {@code properties},
 * {@code required}, {@code additionalProperties: false}, {@code enum}, {@code items}, {@code minimum},
 * {@code maximum}. Other keywords are ignored, so a richer schema is still sent to the model unchanged.
 * Errors name the path ({@code items[1].name: expected string}) so the model can fix its call.
 */
public final class Schema {

  private Schema() {
  }

  /** The problems of {@code value}; empty when it conforms. */
  public static List<String> validate(JsonNode schema, JsonNode value) {
    List<String> errors = new ArrayList<>();
    check(schema, value, "", errors);
    return errors;
  }

  private static void check(JsonNode schema, JsonNode value, String path, List<String> errors) {
    if (schema == null || !schema.isObject()) return;
    String at = path.isEmpty() ? "arguments" : path;

    JsonNode type = schema.path("type");
    if (!type.isMissingNode() && !matchesType(type, value)) {
      errors.add(at + ": expected " + (type.isArray() ? join(type) : type.asText()) + ", got " + typeOf(value));
      return;
    }

    JsonNode allowed = schema.path("enum");
    if (allowed.isArray()) {
      boolean found = false;
      for (JsonNode a : allowed) {
        if (a.equals(value) || (a.isNumber() && value.isNumber() && a.decimalValue().compareTo(value.decimalValue()) == 0)) {
          found = true;
          break;
        }
      }
      if (!found) errors.add(at + ": must be one of " + allowed);
    }

    if (value.isNumber()) {
      JsonNode min = schema.path("minimum");
      if (min.isNumber() && value.decimalValue().compareTo(min.decimalValue()) < 0) {
        errors.add(at + ": must be >= " + min.asText());
      }
      JsonNode max = schema.path("maximum");
      if (max.isNumber() && value.decimalValue().compareTo(max.decimalValue()) > 0) {
        errors.add(at + ": must be <= " + max.asText());
      }
    }

    if (value.isObject()) {
      JsonNode properties = schema.path("properties");
      Set<String> missing = new HashSet<>();
      for (JsonNode req : schema.path("required")) {
        if (!value.has(req.asText()) || value.get(req.asText()).isNull()) {
          missing.add(req.asText());
          errors.add(child(path, req.asText()) + ": required");
        }
      }
      for (Map.Entry<String, JsonNode> field : value.properties()) {
        if (missing.contains(field.getKey())) continue; // a required null is reported once, as missing
        JsonNode sub = properties.path(field.getKey());
        if (sub.isObject()) {
          check(sub, field.getValue(), child(path, field.getKey()), errors);
        } else if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean()) {
          errors.add(child(path, field.getKey()) + ": unknown property");
        }
      }
    }

    if (value.isArray() && schema.path("items").isObject()) {
      for (int i = 0; i < value.size(); i++) {
        check(schema.get("items"), value.get(i), at + "[" + i + "]", errors);
      }
    }
  }

  private static boolean matchesType(JsonNode type, JsonNode value) {
    if (type.isArray()) {
      for (JsonNode t : type) {
        if (is(t.asText(), value)) return true;
      }
      return false;
    }
    return is(type.asText(), value);
  }

  private static boolean is(String type, JsonNode value) {
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isTextual();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      case "number" -> value.isNumber();
      case "integer" -> value.isIntegralNumber()
          || (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0);
      default -> true; // unknown type keyword: do not reject what we cannot judge
    };
  }

  private static String typeOf(JsonNode value) {
    if (value.isObject()) return "object";
    if (value.isArray()) return "array";
    if (value.isTextual()) return "string";
    if (value.isBoolean()) return "boolean";
    if (value.isNull()) return "null";
    if (value.isIntegralNumber()) return "integer";
    if (value.isNumber()) return "number";
    return value.getNodeType().name().toLowerCase(Locale.ROOT);
  }

  private static String child(String path, String name) {
    return path.isEmpty() ? name : path + "." + name;
  }

  private static String join(JsonNode types) {
    List<String> names = new ArrayList<>();
    types.forEach(t -> names.add(t.asText()));
    return String.join(" or ", names);
  }
}

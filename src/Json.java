import java.util.List;

/**
 * Tiny helper for writing JSON by hand, so the demo needs no libraries.
 * A real project would use a JSON library such as Jackson or Gson.
 */
final class Json {

    private Json() { }

    /** Turns a Java String into a JSON string, escaping anything that would break it. */
    static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Joins already-built JSON values into a JSON array. */
    static String array(List<String> jsonItems) {
        return "[" + String.join(",", jsonItems) + "]";
    }
}

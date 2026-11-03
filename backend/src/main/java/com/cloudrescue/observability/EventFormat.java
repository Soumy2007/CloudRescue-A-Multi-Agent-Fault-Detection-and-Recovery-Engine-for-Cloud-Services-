package com.cloudrescue.observability;

import java.util.regex.Pattern;

/**
 * Formats one structured log line as logfmt-style key=value pairs:
 * {@code event=recovery.action.completed incident_id=12 service=payment-service action=RESTART}.
 *
 * Pure and framework-free so it is unit-testable. It also keeps secrets out of logs: values whose KEY looks sensitive
 * are replaced, secret-looking assignments inside values are masked, control characters are removed and long values
 * are truncated.
 */
public final class EventFormat {
    static final int MAX_VALUE_LENGTH = 300;
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i)(password|passwd|secret|token|credential|authorization|api[_-]?key)");
    private static final Pattern SECRET_ASSIGNMENT =
            Pattern.compile("(?i)(password|passwd|secret|token|api[_-]?key)\\s*[=:]\\s*\\S+");

    private EventFormat() {}

    /** @param keyValues alternating key, value, key, value ... ; pairs with a null value are skipped. */
    public static String format(String event, Object... keyValues) {
        StringBuilder sb = new StringBuilder("event=").append(value(event));
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object v = keyValues[i + 1];
            if (v == null) continue;
            sb.append(' ').append(key).append('=')
              .append(SENSITIVE_KEY.matcher(key).find() ? "[REDACTED]" : value(String.valueOf(v)));
        }
        return sb.toString();
    }

    static String value(String raw) {
        String s = SECRET_ASSIGNMENT.matcher(raw).replaceAll("$1=[REDACTED]");
        s = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (s.length() > MAX_VALUE_LENGTH) s = s.substring(0, MAX_VALUE_LENGTH) + "...";
        boolean needsQuotes = s.isEmpty() || s.indexOf(' ') >= 0 || s.indexOf('"') >= 0 || s.indexOf('=') >= 0;
        return needsQuotes ? "\"" + s.replace("\"", "'") + "\"" : s;
    }
}

package com.cloudrescue.observability;

import org.slf4j.Logger;

/** Thin SLF4J wrapper that writes {@link EventFormat} lines. Keeps log events consistent across modules. */
public final class EventLog {
    private EventLog() {}

    public static void info(Logger log, String event, Object... keyValues) {
        if (log.isInfoEnabled()) log.info(EventFormat.format(event, keyValues));
    }

    public static void warn(Logger log, String event, Object... keyValues) {
        if (log.isWarnEnabled()) log.warn(EventFormat.format(event, keyValues));
    }
}

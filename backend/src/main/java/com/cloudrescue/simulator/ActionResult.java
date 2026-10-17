package com.cloudrescue.simulator;

/** Outcome of a remediation side effect, serialisable to the idempotency store as "OK:..." / "FAIL:...". */
public record ActionResult(boolean success, String message) {
    public static ActionResult ok(String m) { return new ActionResult(true, m); }

    public static ActionResult fail(String m) { return new ActionResult(false, m); }

    public String toStored() { return (success ? "OK:" : "FAIL:") + message; }

    public static ActionResult fromStored(String s) {
        return s.startsWith("OK:") ? ok(s.substring(3)) : fail(s.startsWith("FAIL:") ? s.substring(5) : s);
    }
}

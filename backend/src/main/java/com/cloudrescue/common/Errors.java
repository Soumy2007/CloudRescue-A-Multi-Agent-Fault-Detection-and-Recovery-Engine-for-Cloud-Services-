package com.cloudrescue.common;

/** Domain exceptions mapped to HTTP statuses by GlobalExceptionHandler. */
public final class Errors {
    private Errors() {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String m) { super(m); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String m) { super(m); }
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String m) { super(m); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String m) { super(m); }
    }

    public static class InvalidTransitionException extends ConflictException {
        public InvalidTransitionException(String m) { super(m); }
    }

    /** Same idempotency key used with a different request payload. */
    public static class IdempotencyKeyReuseException extends ConflictException {
        public IdempotencyKeyReuseException(String key) {
            super("Idempotency key '" + key + "' was already used with a different request");
        }
    }

    /** Another worker currently holds this key; the caller should retry later. */
    public static class RequestInProgressException extends ConflictException {
        public RequestInProgressException(String key) {
            super("A request with idempotency key '" + key + "' is already in progress");
        }
    }
}

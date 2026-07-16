package com.example.commander.mq;

/**
 * Result of a {@link ResilientMqSender#send(String, String)} call.
 *
 * @param type which of the four outcomes this is
 * @param cause the failure's cause, or {@code null} on {@link Type#SUCCESS}
 */
public record SendOutcome(Type type, Throwable cause) {

    public enum Type {
        /** The message was sent successfully. */
        SUCCESS,
        /** The circuit breaker was open — no connection attempt was made at all. */
        BREAKER_OPEN,
        /** Every retry attempt was used and the send still failed. */
        TRANSIENT_EXHAUSTED,
        /** The failure was classified permanent — sent once, not retried. */
        PERMANENT
    }

    public static SendOutcome success() {
        return new SendOutcome(Type.SUCCESS, null);
    }

    public static SendOutcome breakerOpen() {
        return new SendOutcome(Type.BREAKER_OPEN, null);
    }

    public static SendOutcome transientExhausted(Throwable cause) {
        return new SendOutcome(Type.TRANSIENT_EXHAUSTED, cause);
    }

    public static SendOutcome permanent(Throwable cause) {
        return new SendOutcome(Type.PERMANENT, cause);
    }

    /**
     * Returns whether this outcome represents a failure to deliver — anything but
     * {@link Type#SUCCESS}.
     *
     * @return {@code true} if the message was not delivered
     */
    public boolean isFailure() {
        return type != Type.SUCCESS;
    }
}

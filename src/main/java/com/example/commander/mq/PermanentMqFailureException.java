package com.example.commander.mq;

/**
 * Internal marker wrapping a send failure {@link MqFailureClassifier} classified permanent —
 * excluded from {@link ResilientMqSender}'s {@code RetryTemplate} retry scope, so a
 * permanent failure is sent once and routed straight to dead-letter.
 */
class PermanentMqFailureException extends RuntimeException {

    PermanentMqFailureException(Throwable cause) {
        super(cause);
    }
}

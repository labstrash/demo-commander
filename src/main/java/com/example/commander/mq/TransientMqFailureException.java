package com.example.commander.mq;

/**
 * Internal marker wrapping a send failure {@link MqFailureClassifier} classified transient —
 * exists solely to scope {@link ResilientMqSender}'s {@code RetryTemplate} to retrying only
 * this type, not every exception a send attempt could throw.
 */
class TransientMqFailureException extends RuntimeException {

    TransientMqFailureException(Throwable cause) {
        super(cause);
    }
}

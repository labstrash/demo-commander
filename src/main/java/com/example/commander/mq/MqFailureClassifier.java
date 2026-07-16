package com.example.commander.mq;

import org.springframework.jms.InvalidDestinationException;
import org.springframework.jms.JmsException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Single policy deciding transient vs. permanent MQ send failures, shared by the primary
 * writer and the dead-letter recovery job so a failure is never classified differently
 * depending on which caller hit it.
 *
 * <p><b>Permanent</b> — retrying changes nothing: {@link InvalidDestinationException} (the
 * resolved queue name doesn't exist on the broker) and {@link JacksonException} (payload
 * serialization failure, thrown before any JMS call is even made). Anything else
 * unrecognized also defaults to permanent — an unknown failure mode shouldn't be blindly
 * retried on the assumption it's transient.
 *
 * <p><b>Transient</b> — worth retrying: any other {@link JmsException}, which is what
 * {@code JmsTemplate} translates a broker-unreachable/connection-reset/timeout {@code
 * jakarta.jms.JMSException} into.
 */
@Component
public class MqFailureClassifier {

    /**
     * Returns whether the given failure is worth retrying.
     *
     * @param ex the exception thrown by the send attempt
     * @return {@code true} if transient (connection-level, worth retrying), {@code false} if
     *     permanent (the message itself is the problem, or the failure mode is unrecognized)
     */
    public boolean isTransient(Throwable ex) {
        if (ex instanceof InvalidDestinationException) {
            return false;
        }
        if (ex instanceof JacksonException) {
            return false;
        }
        return ex instanceof JmsException;
    }
}

package com.example.commander.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * The one send path both the primary writer and the dead-letter recovery job go through:
 * circuit breaker gate → classify-and-retry transient failures → report the outcome. Neither
 * caller talks to {@link JmsTemplate} directly, and neither implements its own
 * retry/breaker/classification logic — see the MQ resilience decisions this centralizes.
 *
 * <p>Takes the already-serialized payload, not the domain object — the recovery job resends
 * a dead-lettered row's stored {@code message_payload} byte-for-byte, with nothing to
 * (re-)serialize.
 */
@Component
public class ResilientMqSender {

    private static final Logger log = LoggerFactory.getLogger(ResilientMqSender.class);

    private final JmsTemplate jmsTemplate;
    private final MqFailureClassifier classifier;
    private final MqCircuitBreaker circuitBreaker;
    private final RetryTemplate retryTemplate;

    public ResilientMqSender(
            JmsTemplate jmsTemplate,
            MqFailureClassifier classifier,
            MqCircuitBreaker circuitBreaker,
            RetryTemplate mqRetryTemplate) {
        this.jmsTemplate = jmsTemplate;
        this.classifier = classifier;
        this.circuitBreaker = circuitBreaker;
        this.retryTemplate = mqRetryTemplate;
    }

    /**
     * Sends {@code payload} to {@code queue}, gated by the circuit breaker and retrying
     * transient failures up to the configured attempt count.
     *
     * @param queue the target MQ queue name
     * @param payload the already-serialized message body
     * @return the outcome — never throws for a send failure, only reports it
     */
    public SendOutcome send(String queue, String payload) {
        if (!circuitBreaker.allowRequest()) {
            log.warn("Circuit breaker open — skipping send to queue={} without attempting a connection", queue);
            return SendOutcome.breakerOpen();
        }

        try {
            retryTemplate.execute(context -> {
                try {
                    jmsTemplate.convertAndSend(queue, payload);
                } catch (RuntimeException ex) {
                    if (classifier.isTransient(ex)) {
                        throw new TransientMqFailureException(ex);
                    }
                    throw new PermanentMqFailureException(ex);
                }
                return null;
            });
            circuitBreaker.recordSuccess();
            return SendOutcome.success();
        } catch (TransientMqFailureException ex) {
            circuitBreaker.recordFailure();
            log.warn("Send to queue={} exhausted every retry attempt", queue, ex.getCause());
            return SendOutcome.transientExhausted(ex.getCause());
        } catch (PermanentMqFailureException ex) {
            log.warn("Send to queue={} failed permanently, not retried", queue, ex.getCause());
            return SendOutcome.permanent(ex.getCause());
        }
    }
}

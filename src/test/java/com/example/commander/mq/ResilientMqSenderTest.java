package com.example.commander.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.InvalidDestinationException;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

@ExtendWith(MockitoExtension.class)
class ResilientMqSenderTest {

    @Mock
    private JmsTemplate jmsTemplate;

    private static final int MAX_ATTEMPTS = 3;

    @Test
    void transientFailureIsRetriedUntilExhaustedThenReportedTransientExhausted() {
        doThrow(new UncategorizedJmsException("broker unreachable"))
                .when(jmsTemplate)
                .convertAndSend(anyString(), any(Object.class));
        ResilientMqSender sender = newSender();

        SendOutcome outcome = sender.send("Q1", "payload");

        assertThat(outcome.type()).isEqualTo(SendOutcome.Type.TRANSIENT_EXHAUSTED);
        verify(jmsTemplate, times(MAX_ATTEMPTS)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void permanentFailureIsSentOnceNotRetried() {
        doThrow(new InvalidDestinationException(new jakarta.jms.InvalidDestinationException("no such queue")))
                .when(jmsTemplate)
                .convertAndSend(anyString(), any(Object.class));
        ResilientMqSender sender = newSender();

        SendOutcome outcome = sender.send("Q1", "payload");

        assertThat(outcome.type()).isEqualTo(SendOutcome.Type.PERMANENT);
        verify(jmsTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void successfulSendReportsSuccess() {
        ResilientMqSender sender = newSender();

        SendOutcome outcome = sender.send("Q1", "payload");

        assertThat(outcome.type()).isEqualTo(SendOutcome.Type.SUCCESS);
        verify(jmsTemplate, times(1)).convertAndSend("Q1", "payload");
    }

    @Test
    void openBreakerSkipsTheJmsCallEntirely() {
        doThrow(new UncategorizedJmsException("broker unreachable"))
                .when(jmsTemplate)
                .convertAndSend(anyString(), any(Object.class));

        MqResilienceProperties properties = properties();
        properties.setBreakerFailureThreshold(1);
        MqCircuitBreaker breaker = new MqCircuitBreaker(properties, Clock.systemUTC());
        ResilientMqSender sender =
                new ResilientMqSender(jmsTemplate, new MqFailureClassifier(), breaker, retryTemplate(properties));

        SendOutcome first = sender.send("Q1", "payload");
        assertThat(first.type()).isEqualTo(SendOutcome.Type.TRANSIENT_EXHAUSTED);
        verify(jmsTemplate, times(MAX_ATTEMPTS)).convertAndSend(anyString(), any(Object.class));

        SendOutcome second = sender.send("Q1", "payload");
        assertThat(second.type()).isEqualTo(SendOutcome.Type.BREAKER_OPEN);

        // still exactly MAX_ATTEMPTS total - the second send() never touched JmsTemplate at all
        verify(jmsTemplate, times(MAX_ATTEMPTS)).convertAndSend(anyString(), any(Object.class));
    }

    private ResilientMqSender newSender() {
        MqResilienceProperties properties = properties();
        MqCircuitBreaker breaker = new MqCircuitBreaker(properties, Clock.systemUTC());
        return new ResilientMqSender(jmsTemplate, new MqFailureClassifier(), breaker, retryTemplate(properties));
    }

    private static org.springframework.retry.support.RetryTemplate retryTemplate(MqResilienceProperties properties) {
        return new MqResilienceConfig().mqRetryTemplate(properties);
    }

    private static MqResilienceProperties properties() {
        MqResilienceProperties properties = new MqResilienceProperties();
        properties.setRetryMaxAttempts(MAX_ATTEMPTS);
        properties.setRetryBackoffMs(1);
        properties.setBreakerFailureThreshold(100); // effectively disabled unless overridden per-test
        properties.setBreakerCoolDownSeconds(30);
        return properties;
    }
}

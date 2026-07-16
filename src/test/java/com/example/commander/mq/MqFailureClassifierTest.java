package com.example.commander.mq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jms.InvalidDestinationException;
import org.springframework.jms.UncategorizedJmsException;
import tools.jackson.core.JacksonException;

class MqFailureClassifierTest {

    private final MqFailureClassifier classifier = new MqFailureClassifier();

    @Test
    void invalidDestinationIsPermanent() {
        InvalidDestinationException ex =
                new InvalidDestinationException(new jakarta.jms.InvalidDestinationException("no such queue"));

        assertThat(classifier.isTransient(ex)).isFalse();
    }

    @Test
    void payloadSerializationFailureIsPermanent() {
        JacksonException ex = new JacksonException("serialization boom") {};

        assertThat(classifier.isTransient(ex)).isFalse();
    }

    @Test
    void otherJmsExceptionIsTransient() {
        UncategorizedJmsException ex = new UncategorizedJmsException("broker unreachable");

        assertThat(classifier.isTransient(ex)).isTrue();
    }

    @Test
    void unrecognizedExceptionDefaultsToPermanent() {
        RuntimeException ex = new RuntimeException("something else entirely");

        assertThat(classifier.isTransient(ex)).isFalse();
    }
}

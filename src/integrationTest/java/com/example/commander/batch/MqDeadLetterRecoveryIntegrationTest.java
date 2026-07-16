package com.example.commander.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commander.repository.DeadLetterMessageRepository;
import com.example.commander.scheduling.DeadLetterRecoveryJob;
import jakarta.jms.ConnectionFactory;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;

/**
 * Verifies the dead-letter recovery round-trip against real infrastructure: a row parked in
 * {@code CAMT.DeadLetterMessage} gets picked up, actually delivered to MQ, and deleted —
 * not the circuit-breaker-under-a-real-outage scenario, which is a network fault to
 * simulate reliably in this docker-compose setup and is verified manually instead (same
 * technique as the live checks used elsewhere in this project).
 *
 * <p>{@link DeadLetterRecoveryJob} isn't part of {@link BatchIntegrationTestConfig}'s
 * component scan (that would also pull in unrelated {@code com.example.commander.scheduling}
 * beans this test doesn't need), so it's imported directly here instead.
 *
 * <p>Targets {@code CAMT.054D.QUEUE} specifically — {@code CAMT053E}/{@code CAMT054C} are
 * already used by other integration tests in this suite that send real messages and never
 * consume them, and this test's exact-match assertion on the received payload isn't
 * tolerant of an unrelated leftover message sitting ahead of it in a shared queue.
 *
 * <p>Requires the local {@code docker-compose} SQL Server and IBM MQ containers (with the
 * full init chain, including {@code 06-schema-audit-deadletter.sql} and {@code config.mqsc})
 * already running.
 */
@SpringBootTest(classes = BatchIntegrationTestConfig.class)
@Import(DeadLetterRecoveryJob.class)
class MqDeadLetterRecoveryIntegrationTest {

    private static final String TARGET_QUEUE = "CAMT.054D.QUEUE";

    @Autowired
    private DeadLetterMessageRepository deadLetterRepository;

    @Autowired
    private DeadLetterRecoveryJob recoveryJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    void recoversAPendingRowAndDeliversItToTheQueue() throws Exception {
        String messageId = "TEST-RECOVERY-" + System.nanoTime();
        String payload = "{\"messageId\":\"" + messageId + "\",\"reportType\":\"CAMT054D\"}";

        deadLetterRepository.insert(
                messageId,
                1L,
                null,
                "CAMT054D",
                payload,
                TARGET_QUEUE,
                5,
                Instant.now().minusSeconds(60));

        recoveryJob.execute(null);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM CAMT.DeadLetterMessage WHERE message_id = ?", Integer.class, messageId);
        assertThat(remaining)
                .describedAs("recovered row must be deleted, not left with a terminal status")
                .isZero();

        JmsTemplate receiver = new JmsTemplate(connectionFactory);
        receiver.setReceiveTimeout(5000);
        String received = (String) receiver.receiveAndConvert(TARGET_QUEUE);
        assertThat(received).isEqualTo(payload);
    }
}

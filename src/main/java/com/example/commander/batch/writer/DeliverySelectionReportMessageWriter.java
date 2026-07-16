package com.example.commander.batch.writer;

import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.PipelineReportMessage;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Delegates each chunk to both {@link LoggingReportMessageWriter} (payload visibility) and
 * {@link MqReportMessageWriter} (the real send), unconditionally, logging first.
 */
@Component
public class DeliverySelectionReportMessageWriter implements ItemWriter<PipelineReportMessage> {

    private final LoggingReportMessageWriter loggingWriter;
    private final MqReportMessageWriter mqWriter;

    public DeliverySelectionReportMessageWriter(
            LoggingReportMessageWriter loggingWriter, MqReportMessageWriter mqWriter) {
        this.loggingWriter = loggingWriter;
        this.mqWriter = mqWriter;
    }

    @Override
    public void write(Chunk<? extends PipelineReportMessage> chunk) throws Exception {
        loggingWriter.write(unwrap(chunk));
        mqWriter.write(chunk);
    }

    private static Chunk<OutboundReportMessage> unwrap(Chunk<? extends PipelineReportMessage> chunk) {
        return new Chunk<>(
                chunk.getItems().stream().map(PipelineReportMessage::payload).toList());
    }
}

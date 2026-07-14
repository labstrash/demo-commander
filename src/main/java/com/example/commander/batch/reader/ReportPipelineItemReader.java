package com.example.commander.batch.reader;

import com.example.commander.config.ReadLayerProperties;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
import com.example.commander.domain.message.MessageAssemblyContext;
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.domain.message.TriggerType;
import com.example.commander.repository.ConfigurationReadRepository;
import com.example.commander.service.FanOutAssemblyService;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.support.AbstractItemStreamItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads active configs for one (reportType, reportFrequency) job firing, page by page,
 * assembling each page into hierarchy trees and fanning each tree out into outbound
 * messages — replacing the inline loop previously in {@code ScheduledConfigReader}.
 *
 * <p>Chunk boundaries (commit-interval) are message-sized, not tree- or page-sized (see
 * the batch pipeline implementation guide, Decision 2), so a page's messages may span
 * multiple chunks. This reader buffers a page's trees, grouped per-tree, and serves items
 * out of that buffer via {@link #read()}, fetching the next page only once the buffer
 * drains.
 *
 * <h2>Checkpoint: tree completion, tracked in drain order</h2>
 *
 * <p>{@code lastSeenId} is the config ID of the last {@link ReportConfigTree} whose entire
 * fan-out has been <b>drained through {@code read()}</b> — not merely assembled into the
 * buffer. A page's trees are assembled all at once, ahead of chunk-by-chunk consumption,
 * so several trees' worth of messages can sit fully buffered-but-unread when Spring Batch
 * calls {@link #update(ExecutionContext)}. If {@code lastSeenId} advanced as soon as a
 * tree was assembled rather than drained, a restart could skip messages that were
 * computed but never actually written — data loss, not just inefficiency.
 *
 * <p>Zero-fan-out trees (no active assignments) have nothing to drain, so waiting for a
 * {@link #read()} call to trigger their advancement would wait forever. They're folded
 * into the checkpoint eagerly instead — as soon as every tree before them is already
 * drained, they count as instantly drained too. This never applies to a tree with unread
 * buffered items, only to genuinely empty ones.
 *
 * <p>This is an efficiency optimization on restart, not the mechanism that prevents
 * duplicate sends — that's the responsibility of future audit dedup logic. A tree
 * interrupted mid-drain may have some of its messages re-emitted after restart, bounded by
 * at most one tree's worth.
 */
@Component
@StepScope
public class ReportPipelineItemReader extends AbstractItemStreamItemReader<OutboundReportMessage> {

    private static final Logger log = LoggerFactory.getLogger(ReportPipelineItemReader.class);

    private static final String LAST_SEEN_ID_KEY = "reportPipeline.lastSeenId";

    private final ConfigurationReadRepository repository;
    private final FanOutAssemblyService fanOutAssemblyService;
    private final int pageSize;

    private final String reportType;
    private final String reportFrequency;
    private final Instant windowStartUtc;
    private final Instant windowEndUtc;

    private final Deque<TreeGroup> pendingGroups = new ArrayDeque<>();
    private long lastSeenId;
    private boolean noMorePages;

    public ReportPipelineItemReader(
            ConfigurationReadRepository repository,
            FanOutAssemblyService fanOutAssemblyService,
            ReadLayerProperties readLayerProperties,
            @Value("#{jobParameters['reportType']}") String reportType,
            @Value("#{jobParameters['reportFrequency']}") String reportFrequency,
            @Value("#{jobParameters['windowStartUtc']}") Instant windowStartUtc,
            @Value("#{jobParameters['windowEndUtc']}") Instant windowEndUtc) {
        this.repository = repository;
        this.fanOutAssemblyService = fanOutAssemblyService;
        this.pageSize = readLayerProperties.getPageSize();
        this.reportType = reportType;
        this.reportFrequency = reportFrequency;
        this.windowStartUtc = windowStartUtc;
        this.windowEndUtc = windowEndUtc;
        setName(ReportPipelineItemReader.class.getSimpleName());
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        lastSeenId = executionContext.getLong(LAST_SEEN_ID_KEY, 0L);
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putLong(LAST_SEEN_ID_KEY, lastSeenId);
    }

    @Override
    public OutboundReportMessage read() {
        while (pendingGroups.isEmpty() && !noMorePages) {
            fetchNextPage();
        }

        if (pendingGroups.isEmpty()) {
            return null;
        }

        TreeGroup front = pendingGroups.peekFirst();
        OutboundReportMessage item = front.items.pollFirst();
        if (front.items.isEmpty()) {
            advanceEagerlyThroughEmptyGroups();
        }
        return item;
    }

    private void fetchNextPage() {
        List<ReportConfigRow> page = repository.findConfigPage(reportType, reportFrequency, lastSeenId, pageSize);
        if (page.isEmpty() || page.size() < pageSize) {
            noMorePages = true;
        }
        if (page.isEmpty()) {
            return;
        }

        List<ReportConfigTree> trees = repository.assembleTrees(page);
        for (ReportConfigTree tree : trees) {
            List<OutboundReportMessage> messages = fanOutAssemblyService.assemble(tree, contextFor(tree));
            pendingGroups.addLast(new TreeGroup(tree.config().id(), new ArrayDeque<>(messages)));
        }
        log.info(
                "Assembled page for reportType={}, frequency={}: {} configs, {} trees",
                reportType,
                reportFrequency,
                page.size(),
                trees.size());

        advanceEagerlyThroughEmptyGroups();
    }

    /**
     * Pops and checkpoints past any run of zero-item groups at the front of the queue.
     * Called after a page is assembled (to fold a leading run of zero-fan-out trees) and
     * after every successful item pop (to fold any run immediately following a tree that
     * just finished draining) — both cases reduce to the same rule: advance past a group
     * only once it holds no more items.
     */
    private void advanceEagerlyThroughEmptyGroups() {
        while (!pendingGroups.isEmpty() && pendingGroups.peekFirst().items.isEmpty()) {
            TreeGroup drained = pendingGroups.pollFirst();
            lastSeenId = drained.configId;
        }
    }

    private MessageAssemblyContext contextFor(ReportConfigTree tree) {
        ReportConfigRow config = tree.config();
        RecipientRef placeholderRecipient =
                new RecipientRef(config.messageRecipientId(), "UNRESOLVED", "UNRESOLVED", "UNRESOLVED");
        return new MessageAssemblyContext(
                windowStartUtc,
                windowEndUtc,
                config.reportVersion(),
                TriggerType.SCHEDULED,
                placeholderRecipient,
                null);
    }

    private record TreeGroup(long configId, Deque<OutboundReportMessage> items) {}
}

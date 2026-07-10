package com.example.commander.service;

import com.example.commander.config.ReadLayerProperties;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
import com.example.commander.domain.message.MessageAssemblyContext;
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.RecipientRef;
import com.example.commander.domain.message.TriggerType;
import com.example.commander.repository.ConfigurationReadRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drives the keyset-paginated read for a single (reportType, reportFrequency) job firing:
 * reads a page of active configs, assembles each page into hierarchy trees, and runs
 * fan-out/bundling against each tree — repeating until a page comes back short.
 *
 * <p>Fan-out runs per page rather than after accumulating the whole job's trees, so
 * memory stays bounded by page size on this side too, not just the read side.
 *
 * <p>Read-only and does not publish or audit anything yet — that's still TODO in
 * {@code ReportSchedulingJob}. This class exists to exercise the full read, assemble,
 * and fan-out path end to end and make it observable in logs ahead of that step.
 */
@Component
public class ScheduledConfigReader {

    private static final Logger log = LoggerFactory.getLogger(ScheduledConfigReader.class);

    private final ConfigurationReadRepository repository;
    private final FanOutAssemblyService fanOutAssemblyService;
    private final int pageSize;

    public ScheduledConfigReader(
            ConfigurationReadRepository repository,
            FanOutAssemblyService fanOutAssemblyService,
            ReadLayerProperties properties) {
        this.repository = repository;
        this.fanOutAssemblyService = fanOutAssemblyService;
        this.pageSize = properties.getPageSize();
    }

    /**
     * Reads every active config for the given report type/frequency, page by page,
     * assembles each page's hierarchy trees, and fans each tree out into outbound
     * messages using the given window.
     *
     * <p>Re-runs from the start on every call — no persisted {@code lastSeenId} across
     * invocations. Re-processing is expected to become idempotent once audit-based dedup
     * exists downstream, so an exact restart position isn't needed here yet.
     *
     * @param reportType exact report type to match
     * @param reportFrequency exact report frequency to match
     * @param windowStartUtc start of the reporting window for this job firing
     * @param windowEndUtc end of the reporting window for this job firing
     * @return summary counts across the whole read/assemble/fan-out pass
     */
    public ReadSummary readAssembleAndFanOut(
            String reportType, String reportFrequency, Instant windowStartUtc, Instant windowEndUtc) {
        long lastSeenId = 0L;
        int pageCount = 0;
        int treeCount = 0;
        int messageCount = 0;

        while (true) {
            List<ReportConfigRow> page = repository.findConfigPage(reportType, reportFrequency, lastSeenId, pageSize);
            if (page.isEmpty()) {
                break;
            }
            pageCount++;

            List<ReportConfigTree> pageTrees = repository.assembleTrees(page);
            treeCount += pageTrees.size();

            int pageMessageCount = 0;
            for (ReportConfigTree tree : pageTrees) {
                List<OutboundReportMessage> messages =
                        fanOutAssemblyService.assemble(tree, contextFor(tree, windowStartUtc, windowEndUtc));
                pageMessageCount += messages.size();
            }
            messageCount += pageMessageCount;

            log.info(
                    "Read page {} for reportType={}, frequency={}: {} configs, {} trees, {} messages",
                    pageCount,
                    reportType,
                    reportFrequency,
                    page.size(),
                    pageTrees.size(),
                    pageMessageCount);

            lastSeenId = page.getLast().id();

            if (page.size() < pageSize) {
                break;
            }
        }

        log.info(
                "Read complete for reportType={}, frequency={}: {} pages, {} trees, {} messages",
                reportType,
                reportFrequency,
                pageCount,
                treeCount,
                messageCount);

        return new ReadSummary(pageCount, treeCount, messageCount);
    }

    /**
     * Builds the fan-out context for a single tree.
     *
     * <p>Recipient type/value/name aren't resolved at this layer — only
     * {@code messageRecipientId} is available on {@code ReportConfigRow} — so this uses a
     * placeholder {@link RecipientRef} carrying just the id. Real recipient resolution
     * belongs to whichever step actually publishes the message; this placeholder exists
     * only so fan-out can run end to end and message counts are observable ahead of that
     * step being built.
     */
    private MessageAssemblyContext contextFor(ReportConfigTree tree, Instant windowStartUtc, Instant windowEndUtc) {
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

    /** Summary counts for one job firing's read/assemble/fan-out pass. */
    public record ReadSummary(int pageCount, int treeCount, int messageCount) {}
}

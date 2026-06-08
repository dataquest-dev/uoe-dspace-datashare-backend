/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare.event;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Logger;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.event.Consumer;
import org.dspace.event.Event;
import uk.ac.ed.datashare.DatashareTemporalCoverage;

/**
 * On item install (archival), encode the transient "Temporal Coverage" dates from
 * {@code dc.coverage.startDate} / {@code dc.coverage.endDate} into the canonical
 * {@code dc.coverage.temporal} value and clear the individual date fields, so the archived record
 * matches production (a single encoded temporal value, no individual date fields). See
 * {@link DatashareTemporalCoverage}.
 *
 * <p>Registered for {@code Item+Install}. DataCite/DOI registration is deferred (the {@code doi}
 * consumer is not on the default dispatcher; DOIs are minted by the doi-organiser job), so this
 * transform always runs before the DataCite metadata is generated.</p>
 */
public class DatashareTemporalCoverageConsumer implements Consumer {

    private static final Logger log =
            org.apache.logging.log4j.LogManager.getLogger(DatashareTemporalCoverageConsumer.class);

    private ItemService itemService;

    /** Items installed during this event cycle, processed in {@link #end(Context)}. */
    private Set<UUID> itemsToProcess;

    @Override
    public void initialize() throws Exception {
        itemService = ContentServiceFactory.getInstance().getItemService();
    }

    @Override
    public void consume(Context ctx, Event event) throws Exception {
        if (event.getSubjectType() != Constants.ITEM || event.getEventType() != Event.INSTALL) {
            return;
        }
        if (itemsToProcess == null) {
            // LinkedHashSet: O(1) de-duplication of repeated installs while preserving order
            itemsToProcess = new LinkedHashSet<>();
        }
        itemsToProcess.add(event.getSubjectID());
    }

    @Override
    public void end(Context ctx) throws Exception {
        if (itemsToProcess == null) {
            return;
        }
        try {
            ctx.turnOffAuthorisationSystem();
            for (UUID id : itemsToProcess) {
                Item item = itemService.find(ctx, id);
                if (item == null) {
                    continue;
                }
                try {
                    DatashareTemporalCoverage.mergeDatesIntoTemporal(ctx, item, itemService);
                } catch (Exception e) {
                    log.error("Datashare temporal-coverage merge failed for item {}", id, e);
                }
            }
        } finally {
            ctx.restoreAuthSystemState();
            itemsToProcess = null;
        }
    }

    @Override
    public void finish(Context ctx) throws Exception {
        itemsToProcess = null;
    }
}

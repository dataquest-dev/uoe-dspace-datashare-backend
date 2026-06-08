/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare.event;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.event.Consumer;
import org.dspace.event.Event;

/**
 * Consolidates Datashare temporal coverage metadata when an item is archived.
 *
 * <p>During submission/workflow the {@code DatashareSpatialAndTemporalStep} keeps both the
 * individual {@code dc.coverage.startDate} / {@code dc.coverage.endDate} fields (so the Angular
 * submission form can edit them) and the canonical encoded {@code dc.coverage.temporal} value
 * (used by the QDC/MODS/OAI export crosswalks). On the final archived item we only want the
 * canonical {@code dc.coverage.temporal} value — keeping all three would leave duplicate
 * temporal-coverage metadata on the public record.</p>
 *
 * <p>When an item is installed (archived) this consumer removes the individual
 * {@code dc.coverage.startDate} / {@code dc.coverage.endDate} fields, but only when the canonical
 * {@code dc.coverage.temporal} value is present, so no data is lost if the temporal value was
 * never encoded (e.g. an incomplete date pair, or an item created outside the submission step).</p>
 *
 * <p>The individual fields are intentionally left untouched while the item is still in submission or
 * workflow (this consumer only acts on the INSTALL event), so editing the item during the approval
 * workflow still loads the temporal dates into the form.</p>
 */
public class DatashareTemporalCoverageConsumer implements Consumer {

    private static final Logger log = LogManager.getLogger(DatashareTemporalCoverageConsumer.class);

    private static final String SCHEMA = "dc";
    private static final String ELEMENT = "coverage";
    private static final String TEMPORAL = "temporal";
    private static final String START_DATE = "startDate";
    private static final String END_DATE = "endDate";

    private ItemService itemService;

    private Set<Item> itemsToProcess;

    @Override
    public void initialize() throws Exception {
        itemService = ContentServiceFactory.getInstance().getItemService();
    }

    @Override
    public void consume(Context ctx, Event event) throws Exception {
        if (itemsToProcess == null) {
            itemsToProcess = new HashSet<>();
        }

        // only react to items being installed (i.e. archived)
        if (event.getSubjectType() != Constants.ITEM || event.getEventType() != Event.INSTALL) {
            return;
        }

        Item item = (Item) event.getSubject(ctx);
        if (item == null || !item.isArchived()) {
            return;
        }

        // Only consolidate once the canonical temporal value exists; otherwise keep the individual
        // fields so no temporal information is lost.
        List<MetadataValue> temporal = itemService.getMetadataByMetadataString(
                item, mdString(TEMPORAL));
        if (temporal.isEmpty()) {
            return;
        }

        List<MetadataValue> startDates = itemService.getMetadataByMetadataString(item, mdString(START_DATE));
        List<MetadataValue> endDates = itemService.getMetadataByMetadataString(item, mdString(END_DATE));
        if (startDates.isEmpty() && endDates.isEmpty()) {
            return;
        }

        log.info("DatashareTemporalCoverageConsumer: consolidating temporal coverage for archived item {} "
                + "(removing individual startDate/endDate, keeping dc.coverage.temporal)", item.getID());
        itemService.clearMetadata(ctx, item, SCHEMA, ELEMENT, START_DATE, Item.ANY);
        itemService.clearMetadata(ctx, item, SCHEMA, ELEMENT, END_DATE, Item.ANY);
        itemsToProcess.add(item);
    }

    @Override
    public void end(Context ctx) throws Exception {
        if (itemsToProcess != null) {
            for (Item item : itemsToProcess) {
                ctx.turnOffAuthorisationSystem();
                try {
                    itemService.update(ctx, item);
                } finally {
                    ctx.restoreAuthSystemState();
                }
            }
            itemsToProcess = null;
        }
    }

    @Override
    public void finish(Context ctx) throws Exception {
        // nothing to do
    }

    private static String mdString(String qualifier) {
        return SCHEMA + "." + ELEMENT + "." + qualifier;
    }
}

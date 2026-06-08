/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.event.Event;
import org.junit.Test;
import uk.ac.ed.datashare.event.DatashareTemporalCoverageConsumer;

/**
 * Integration tests for the "Temporal Coverage" round-trip:
 * <ul>
 *   <li>on archive (Item INSTALL) the {@link DatashareTemporalCoverageConsumer} encodes
 *       dc.coverage.startDate / dc.coverage.endDate into dc.coverage.temporal and clears the
 *       individual date fields;</li>
 *   <li>the reverse split (used by {@link DatashareItemVersionProvider} for a new version) decodes
 *       dc.coverage.temporal back into dc.coverage.startDate / dc.coverage.endDate.</li>
 * </ul>
 */
public class DatashareTemporalCoverageConsumerIT extends AbstractIntegrationTestWithDatabase {

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();

    private Item createArchivedItem() throws Exception {
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();
        return ItemBuilder.createItem(context, collection)
                .withTitle("Datashare temporal item")
                .withIssueDate("2024-01-17")
                .build();
    }

    private List<String> values(Item item, String field) {
        return itemService.getMetadataByMetadataString(item, field).stream()
                .map(MetadataValue::getValue).collect(Collectors.toList());
    }

    /** Fire the Item INSTALL event directly through the consumer, as item installation (archival) does. */
    private void fireInstall(Item item) throws Exception {
        DatashareTemporalCoverageConsumer consumer = new DatashareTemporalCoverageConsumer();
        consumer.initialize();
        consumer.consume(context, new Event(Event.INSTALL, Constants.ITEM, item.getID(), item.getHandle()));
        consumer.end(context);
    }

    @Test
    public void installEncodesDatesIntoTemporalAndClearsIndividualFields() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        itemService.addMetadata(context, item, "dc", "coverage", "startDate", null, "2021-04-15");
        itemService.addMetadata(context, item, "dc", "coverage", "endDate", null, "2023-09-20");
        itemService.update(context, item);

        fireInstall(item);
        context.restoreAuthSystemState();

        assertEquals("start/end must be encoded into a single temporal value",
                List.of("start=2021-04-15; end=2023-09-20; scheme=W3C-DTF"), values(item, "dc.coverage.temporal"));
        assertTrue("dc.coverage.startDate should be cleared on archive",
                values(item, "dc.coverage.startDate").isEmpty());
        assertTrue("dc.coverage.endDate should be cleared on archive",
                values(item, "dc.coverage.endDate").isEmpty());
    }

    @Test
    public void installEncodesIncompleteDatePair() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        itemService.addMetadata(context, item, "dc", "coverage", "startDate", null, "2021");
        itemService.update(context, item);

        fireInstall(item);
        context.restoreAuthSystemState();

        assertEquals("a lone start date is still encoded (no data loss)",
                List.of("start=2021; end=; scheme=W3C-DTF"), values(item, "dc.coverage.temporal"));
        assertTrue(values(item, "dc.coverage.startDate").isEmpty());
    }

    @Test
    public void installWithoutDatesIsNoop() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();

        fireInstall(item);
        context.restoreAuthSystemState();

        assertTrue("no temporal value should be created when there are no dates",
                values(item, "dc.coverage.temporal").isEmpty());
    }

    @Test
    public void installWithBlankDatesDoesNotCreateEmptyTemporal() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        itemService.addMetadata(context, item, "dc", "coverage", "startDate", null, "");
        itemService.addMetadata(context, item, "dc", "coverage", "endDate", null, "");
        itemService.update(context, item);

        fireInstall(item);
        context.restoreAuthSystemState();

        assertTrue("blank dates must not produce a temporal value",
                values(item, "dc.coverage.temporal").isEmpty());
        assertTrue(values(item, "dc.coverage.startDate").isEmpty());
        assertTrue(values(item, "dc.coverage.endDate").isEmpty());
    }

    @Test
    public void splitDecodesTemporalBackIntoDates() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        itemService.addMetadata(context, item, "dc", "coverage", "temporal", null,
                "start=2021-04-15; end=2023-09-20; scheme=W3C-DTF");
        itemService.update(context, item);

        DatashareTemporalCoverage.splitTemporalIntoDates(context, item, itemService);
        context.restoreAuthSystemState();

        assertTrue("the encoded value should be removed after splitting",
                values(item, "dc.coverage.temporal").isEmpty());
        assertEquals(List.of("2021-04-15"), values(item, "dc.coverage.startDate"));
        assertEquals(List.of("2023-09-20"), values(item, "dc.coverage.endDate"));
    }

    @Test
    public void splitIsNoopWhenIndividualDatesAlreadyPresent() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        itemService.addMetadata(context, item, "dc", "coverage", "temporal", null,
                "start=2021; end=2025; scheme=W3C-DTF");
        itemService.addMetadata(context, item, "dc", "coverage", "startDate", null, "2099");
        itemService.update(context, item);

        DatashareTemporalCoverage.splitTemporalIntoDates(context, item, itemService);
        context.restoreAuthSystemState();

        assertEquals("existing individual dates must not be overwritten",
                List.of("2099"), values(item, "dc.coverage.startDate"));
        assertEquals("temporal is left untouched when individual dates are present",
                List.of("start=2021; end=2025; scheme=W3C-DTF"), values(item, "dc.coverage.temporal"));
    }
}

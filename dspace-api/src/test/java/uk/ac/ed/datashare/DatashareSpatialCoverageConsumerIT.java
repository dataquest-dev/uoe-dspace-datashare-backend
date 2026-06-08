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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import uk.ac.ed.datashare.event.DatashareSpatialCoverageConsumer;

/**
 * Integration tests for the "Spatial Coverage: Country" round-trip:
 * <ul>
 *   <li>on archive (Item INSTALL) the {@link DatashareSpatialCoverageConsumer} moves dc.subject.ddc into
 *       dc.coverage.spatial and clears dc.subject.ddc;</li>
 *   <li>the reverse split (used by {@link DatashareItemVersionProvider} for a new version) re-populates
 *       dc.subject.ddc from dc.coverage.spatial.</li>
 * </ul>
 */
public class DatashareSpatialCoverageConsumerIT extends AbstractIntegrationTestWithDatabase {

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();

    private Item createArchivedItem() throws Exception {
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();
        return ItemBuilder.createItem(context, collection)
                .withTitle("Datashare spatial item")
                .withIssueDate("2024-01-17")
                .build();
    }

    private List<String> values(Item item, String field) {
        return itemService.getMetadataByMetadataString(item, field).stream()
                .map(MetadataValue::getValue).collect(Collectors.toList());
    }

    /** Fire the Item INSTALL event directly through the consumer, as item installation (archival) does. */
    private void fireInstall(Item item) throws Exception {
        DatashareSpatialCoverageConsumer consumer = new DatashareSpatialCoverageConsumer();
        consumer.initialize();
        consumer.consume(context, new Event(Event.INSTALL, Constants.ITEM, item.getID(), item.getHandle()));
        consumer.end(context);
    }

    @Test
    public void installMovesCountryIntoSpatialAndClearsDdc() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        itemService.addMetadata(context, item, "dc", "subject", "ddc", null, "UK");
        itemService.addMetadata(context, item, "dc", "subject", "ddc", null, "DZ");
        itemService.addMetadata(context, item, "dc", "coverage", "spatial", null, "Edinburgh");
        itemService.update(context, item);

        fireInstall(item);
        context.restoreAuthSystemState();

        assertTrue("dc.subject.ddc should be cleared on archive", values(item, "dc.subject.ddc").isEmpty());
        List<String> spatial = values(item, "dc.coverage.spatial");
        assertEquals(3, spatial.size());
        assertTrue(spatial.contains("Edinburgh"));
        assertTrue(spatial.contains("UK"));
        assertTrue(spatial.contains("DZ"));
    }

    @Test
    public void installSkipsBlankCountryValues() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        itemService.addMetadata(context, item, "dc", "subject", "ddc", null, "UK");
        itemService.addMetadata(context, item, "dc", "subject", "ddc", null, "   ");
        itemService.update(context, item);

        fireInstall(item);
        context.restoreAuthSystemState();

        assertEquals("only the non-blank country is moved", List.of("UK"), values(item, "dc.coverage.spatial"));
        assertTrue(values(item, "dc.subject.ddc").isEmpty());
    }

    @Test
    public void splitRepopulatesCountryFromSpatial() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        itemService.addMetadata(context, item, "dc", "coverage", "spatial", null, "Edinburgh");
        itemService.addMetadata(context, item, "dc", "coverage", "spatial", null, "UK");
        itemService.addMetadata(context, item, "dc", "coverage", "spatial", null, "DZ");
        itemService.update(context, item);

        Set<String> countryCodes = new HashSet<>();
        countryCodes.add("UK");
        countryCodes.add("DZ");
        DatashareSpatialCoverage.splitSpatialIntoCountry(context, item, itemService, countryCodes);
        context.restoreAuthSystemState();

        assertEquals(List.of("Edinburgh"), values(item, "dc.coverage.spatial"));
        List<String> ddc = values(item, "dc.subject.ddc");
        assertEquals(2, ddc.size());
        assertTrue(ddc.contains("UK"));
        assertTrue(ddc.contains("DZ"));
    }
}

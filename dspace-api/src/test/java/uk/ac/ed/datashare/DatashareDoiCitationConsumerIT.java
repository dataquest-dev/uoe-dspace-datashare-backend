/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Test;
import uk.ac.ed.datashare.event.DatashareDoiCitationConsumer;

/**
 * Integration tests for {@link DatashareDoiCitationConsumer}:
 * <ul>
 *   <li>on archive (Item INSTALL) the citation is written with the configured DOI placeholder, before
 *       any DOI is registered;</li>
 *   <li>when the doi-organiser job later stores the DOI in dc.identifier.uri (which fires
 *       Item MODIFY_METADATA), the citation is rewritten to embed the real DOI, replacing the
 *       placeholder;</li>
 *   <li>the rewrite is idempotent (no update loop, no duplicate values).</li>
 * </ul>
 */
public class DatashareDoiCitationConsumerIT extends AbstractIntegrationTestWithDatabase {

    private static final String PLACEHOLDER = "DOI: awaiting registration";
    private static final String DOI_URL = "https://doi.org/10.5072/dspace/it-test";

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    private Item createArchivedItem() throws Exception {
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("A DataShare dataset")
                .build();
        itemService.addMetadata(context, item, "dc", "creator", null, null, "Smith, John");
        itemService.addMetadata(context, item, "dc", "date", "available", null, "2024-05-06");
        itemService.addMetadata(context, item, "dc", "type", null, null, "Dataset");
        itemService.update(context, item);
        return item;
    }

    private List<String> values(Item item, String field) {
        return itemService.getMetadataByMetadataString(item, field).stream()
                .map(MetadataValue::getValue).collect(Collectors.toList());
    }

    private String citation(Item item) {
        List<String> citations = values(item, "dc.identifier.citation");
        return citations.isEmpty() ? null : citations.get(0);
    }

    private void fire(Item item, int eventType) throws Exception {
        DatashareDoiCitationConsumer consumer = new DatashareDoiCitationConsumer();
        consumer.initialize();
        consumer.consume(context, new Event(eventType, Constants.ITEM, item.getID(), item.getHandle()));
        consumer.end(context);
    }

    @Test
    public void installWritesCitationWithPlaceholderAndNoDoi() throws Exception {
        configurationService.setProperty(DatashareDoiCitationConsumer.PLACEHOLDER_PROPERTY, PLACEHOLDER);
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();

        fire(item, Event.INSTALL);
        context.restoreAuthSystemState();

        String citation = citation(item);
        assertTrue("a citation must be written at install", citation != null && !citation.isEmpty());
        assertTrue("the citation should contain the placeholder before registration",
                citation.contains(PLACEHOLDER));
        assertFalse("no DOI URL should be present before registration", citation.contains("https://doi.org"));
        assertTrue("the citation should include item metadata", citation.contains("Smith, John"));
    }

    @Test
    public void doiRegistrationFoldsRealDoiIntoCitation() throws Exception {
        configurationService.setProperty(DatashareDoiCitationConsumer.PLACEHOLDER_PROPERTY, PLACEHOLDER);
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        fire(item, Event.INSTALL);

        // Simulate doi-organiser registering the DOI: it stores the resolver URL in dc.identifier.uri,
        // which fires Item MODIFY_METADATA.
        itemService.addMetadata(context, item, "dc", "identifier", "uri", null, DOI_URL);
        itemService.update(context, item);
        fire(item, Event.MODIFY_METADATA);
        context.restoreAuthSystemState();

        String citation = citation(item);
        assertTrue("the real DOI should be embedded once registered", citation.contains(DOI_URL));
        assertFalse("the placeholder should be gone once the DOI is present", citation.contains(PLACEHOLDER));
        assertEquals("there must be exactly one citation value", 1, values(item, "dc.identifier.citation").size());
    }

    @Test
    public void rewriteIsIdempotent() throws Exception {
        configurationService.setProperty(DatashareDoiCitationConsumer.PLACEHOLDER_PROPERTY, PLACEHOLDER);
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItem();
        fire(item, Event.INSTALL);
        itemService.addMetadata(context, item, "dc", "identifier", "uri", null, DOI_URL);
        itemService.update(context, item);
        fire(item, Event.MODIFY_METADATA);
        String first = citation(item);

        // A further Modify_Metadata cycle must not change or duplicate the citation.
        fire(item, Event.MODIFY_METADATA);
        context.restoreAuthSystemState();

        assertEquals("citation must be unchanged on a redundant pass", first, citation(item));
        assertEquals("citation must not be duplicated", 1, values(item, "dc.identifier.citation").size());
    }
}

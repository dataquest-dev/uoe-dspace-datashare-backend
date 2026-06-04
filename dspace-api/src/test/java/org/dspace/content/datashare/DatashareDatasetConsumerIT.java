/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.commons.io.IOUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.BundleBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.event.Event;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Before;
import org.junit.Test;
import uk.ac.ed.datashare.event.DatashareConsumer;

/**
 * Integration tests for the DataShare "download all files" zip lifecycle. When an item's fileset
 * changes (a bitstream is added to / removed from one of the bundles packaged into the zip), the
 * stale dataset zip must be deleted so that it is regenerated from the current files by the
 * {@code ds-datasets} job. See https://github.com/dataquest-dev/dspace-customers/issues/647.
 */
public class DatashareDatasetConsumerIT extends AbstractIntegrationTestWithDatabase {

    private final DatashareDatasetService datasetService =
            ContentServiceFactory.getInstance().getDatashareDatasetService();
    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    private File datasetsDir;

    @Before
    public void configureDatasetsPath() throws Exception {
        datasetsDir = Files.createTempDirectory("datashare-datasets").toFile();
        configurationService.setProperty("datasets.path", datasetsDir.getAbsolutePath());
    }

    private Item createArchivedItemWithFile() throws Exception {
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Datashare item")
                .withIssueDate("2024-01-17")
                .build();
        try (InputStream is = IOUtils.toInputStream("Dataset file content", StandardCharsets.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("dataset-file.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        return item;
    }

    /** Place a fake generated zip on disk, as the ds-datasets job would. */
    private File placeDatasetZipFile(Item item) throws Exception {
        String fileName = DatashareItemDataset.getFileName(item.getHandle());
        File zip = new File(datasetsDir, fileName);
        Files.write(zip.toPath(), "fake-zip".getBytes(StandardCharsets.UTF_8));
        return zip;
    }

    /** Register the dataset DB record for the item, as the ds-datasets job would after zipping. */
    private void registerDatasetRecord(Item item) {
        String fileName = DatashareItemDataset.getFileName(item.getHandle());
        datasetService.insertDatashareDataset(context, item, fileName, "checksum");
    }

    private void fireBundleEvent(int eventType, Bundle bundle) throws Exception {
        DatashareConsumer consumer = new DatashareConsumer();
        consumer.initialize();
        Event event = new Event(eventType, Constants.BUNDLE, bundle.getID(), bundle.getName());
        consumer.consume(context, event);
        consumer.end(context);
    }

    @Test
    public void datasetZipDeletedWhenBitstreamRemovedFromOriginalBundle() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = placeDatasetZipFile(item);
        registerDatasetRecord(item);
        Bundle original = itemService.getBundles(item, "ORIGINAL").get(0);
        context.restoreAuthSystemState();

        assertTrue("precondition: the generated zip exists", zip.exists());

        fireBundleEvent(Event.REMOVE, original);

        assertFalse("the stale zip must be deleted when a file is removed", zip.exists());
    }

    @Test
    public void datasetZipDeletedWhenBitstreamAddedToOriginalBundle() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = placeDatasetZipFile(item);
        registerDatasetRecord(item);
        Bundle original = itemService.getBundles(item, "ORIGINAL").get(0);
        context.restoreAuthSystemState();

        assertTrue("precondition: the generated zip exists", zip.exists());

        fireBundleEvent(Event.ADD, original);

        assertFalse("the stale zip must be deleted when a file is added", zip.exists());
    }

    @Test
    public void datasetZipKeptWhenNonZipBundleChanges() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        // Only a physical zip (no DB record) so the leftover survives test cleanup; the assertion
        // here is purely that an unrelated bundle change does not delete the zip.
        File zip = placeDatasetZipFile(item);
        // A bundle that is not packaged into the zip (e.g. THUMBNAIL, generated by media filters)
        // must not trigger a regeneration.
        Bundle thumbnail = BundleBuilder.createBundle(context, item).withName("THUMBNAIL").build();
        context.restoreAuthSystemState();

        fireBundleEvent(Event.ADD, thumbnail);

        assertTrue("the zip must survive changes to bundles that are not part of it", zip.exists());
    }
}

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
import java.util.Arrays;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.BundleBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.GroupService;
import org.dspace.event.Event;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.ac.ed.datashare.event.DatashareConsumer;

/**
 * Integration tests for the DataShare "download all files" zip lifecycle, restoring the DataShare
 * 6.x behaviour:
 * <ul>
 *   <li>a fresh zip is generated when a new item is archived (installed),</li>
 *   <li>the zip is deleted when the item is removed from its collection,</li>
 *   <li>the stale zip is deleted when the item's fileset changes (a bitstream is added to / removed
 *       from one of the bundles packaged into the zip) so it is regenerated from the current files
 *       by the {@code ds-datasets} job,</li>
 *   <li>the zip is deleted when a file's resource policy is restricted (no longer readable by
 *       Anonymous) and regenerated when the file is made public again.</li>
 * </ul>
 * See https://github.com/dataquest-dev/dspace-customers/issues/647.
 */
public class DatashareDatasetConsumerIT extends AbstractIntegrationTestWithDatabase {

    private final DatashareDatasetService datasetService =
            ContentServiceFactory.getInstance().getDatashareDatasetService();
    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
    private final GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();
    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    private File datasetsDir;
    private String originalDatasetsPath;

    @Before
    public void configureDatasetsPath() throws Exception {
        originalDatasetsPath = configurationService.getProperty("datasets.path");
        datasetsDir = Files.createTempDirectory("datashare-datasets").toFile();
        configurationService.setProperty("datasets.path", datasetsDir.getAbsolutePath());
    }

    @After
    public void restoreDatasetsPath() {
        // ConfigurationService is shared across the JVM; restore datasets.path so this test does
        // not leak into other integration tests and reintroduce order-dependent failures.
        configurationService.setProperty("datasets.path", originalDatasetsPath);
        // Remove the per-test temporary datasets directory so repeated runs do not leave behind
        // many datashare-datasets* directories on disk.
        FileUtils.deleteQuietly(datasetsDir);
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

    /** Whether the file on disk is still the stale placeholder written by {@link #placeDatasetZipFile}. */
    private boolean isStalePlaceholder(File zip) throws Exception {
        return zip.exists()
                && Arrays.equals(Files.readAllBytes(zip.toPath()), "fake-zip".getBytes(StandardCharsets.UTF_8));
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

    /** Fire the event raised when a new item is installed (archived), as the submission flow does. */
    private void fireItemInstallEvent(Item item) throws Exception {
        DatashareConsumer consumer = new DatashareConsumer();
        consumer.initialize();
        Event event = new Event(Event.INSTALL, Constants.ITEM, item.getID(), item.getHandle());
        consumer.consume(context, event);
        consumer.end(context);
    }

    /** Fire the event raised when an item is removed from a collection. */
    private void fireCollectionRemoveItemEvent(Collection collection, Item item) throws Exception {
        DatashareConsumer consumer = new DatashareConsumer();
        consumer.initialize();
        Event event = new Event(Event.REMOVE, Constants.COLLECTION, collection.getID(),
                Constants.ITEM, item.getID(), item.getHandle());
        consumer.consume(context, event);
        consumer.end(context);
    }

    /** Fire the Item MODIFY event raised when an item is withdrawn or reinstated. */
    private void fireItemModifyEvent(Item item, String detail) throws Exception {
        DatashareConsumer consumer = new DatashareConsumer();
        consumer.initialize();
        Event event = new Event(Event.MODIFY, Constants.ITEM, item.getID(), detail);
        consumer.consume(context, event);
        consumer.end(context);
    }

    /** Fire the Item MODIFY_METADATA event raised when an item's metadata (e.g. embargo) changes. */
    private void fireItemModifyMetadataEvent(Item item) throws Exception {
        DatashareConsumer consumer = new DatashareConsumer();
        consumer.initialize();
        Event event = new Event(Event.MODIFY_METADATA, Constants.ITEM, item.getID(), null);
        consumer.consume(context, event);
        consumer.end(context);
    }

    /** Fire the Bitstream MODIFY event raised when a bitstream's resource policy changes. */
    private void fireBitstreamModifyEvent(Bitstream bitstream) throws Exception {
        DatashareConsumer consumer = new DatashareConsumer();
        consumer.initialize();
        Event event = new Event(Event.MODIFY, Constants.BITSTREAM, bitstream.getID(), null);
        consumer.consume(context, event);
        consumer.end(context);
    }

    /** Fire the Bundle MODIFY event raised when a bundle's resource policy changes. */
    private void fireBundleModifyEvent(Bundle bundle) throws Exception {
        DatashareConsumer consumer = new DatashareConsumer();
        consumer.initialize();
        Event event = new Event(Event.MODIFY, Constants.BUNDLE, bundle.getID(), null);
        consumer.consume(context, event);
        consumer.end(context);
    }

    private Bitstream firstOriginalBitstream(Item item) throws Exception {
        return itemService.getBundles(item, "ORIGINAL").get(0).getBitstreams().get(0);
    }

    /** Remove the bitstream's READ policies so it is no longer readable by Anonymous (restricted). */
    private void restrictBitstream(Bitstream bitstream) throws Exception {
        authorizeService.removePoliciesActionFilter(context, bitstream, Constants.READ);
    }

    /** Grant the Anonymous group READ on the bitstream so it is public again. */
    private void makeBitstreamPublic(Bitstream bitstream) throws Exception {
        Group anonymous = groupService.findByName(context, Group.ANONYMOUS);
        authorizeService.addPolicy(context, bitstream, Constants.READ, anonymous);
    }

    private void setEmbargo(Item item, String date) throws Exception {
        itemService.addMetadata(context, item, "dc", "date", "embargo", null, date);
        itemService.update(context, item);
    }

    private void clearEmbargo(Item item) throws Exception {
        itemService.clearMetadata(context, item, "dc", "date", "embargo", Item.ANY);
        itemService.update(context, item);
    }

    @Test
    public void datasetZipCreatedWhenItemInstalled() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = new File(datasetsDir, DatashareItemDataset.getFileName(item.getHandle()));
        context.restoreAuthSystemState();

        assertFalse("precondition: no zip exists for a freshly created item", zip.exists());

        fireItemInstallEvent(item);

        assertTrue("a fresh zip must be generated when a new item is archived", zip.exists());

        // The create path registers a dataset DB record (item_id FK); drop it before the builder
        // teardown deletes the item, so we do not hit a foreign-key violation during cleanup.
        context.turnOffAuthorisationSystem();
        datasetService.deleteDatasetForItem(context, item);
        context.restoreAuthSystemState();
    }

    @Test
    public void datasetZipDeletedWhenItemRemovedFromCollection() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        Collection owningCollection = item.getOwningCollection();
        File zip = placeDatasetZipFile(item);
        registerDatasetRecord(item);
        context.restoreAuthSystemState();

        assertTrue("precondition: the generated zip exists", zip.exists());

        fireCollectionRemoveItemEvent(owningCollection, item);

        assertFalse("the zip must be deleted when the item is removed from its collection", zip.exists());
    }

    @Test
    public void datasetZipDeletedWhenItemWithdrawn() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = placeDatasetZipFile(item);
        registerDatasetRecord(item);
        itemService.withdraw(context, item);
        context.restoreAuthSystemState();

        assertTrue("precondition: the generated zip exists", zip.exists());

        fireItemModifyEvent(item, "WITHDRAW");

        assertFalse("the zip must be deleted when the item is withdrawn", zip.exists());
    }

    @Test
    public void datasetZipCreatedWhenItemReinstated() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = new File(datasetsDir, DatashareItemDataset.getFileName(item.getHandle()));
        itemService.withdraw(context, item);
        itemService.reinstate(context, item);
        context.restoreAuthSystemState();

        assertFalse("precondition: a withdrawn item has no zip", zip.exists());

        fireItemModifyEvent(item, "REINSTATE");

        assertTrue("the zip must be regenerated when the item is reinstated", zip.exists());

        context.turnOffAuthorisationSystem();
        datasetService.deleteDatasetForItem(context, item);
        context.restoreAuthSystemState();
    }

    @Test
    public void datasetZipDeletedWhenEmbargoApplied() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = placeDatasetZipFile(item);
        registerDatasetRecord(item);
        setEmbargo(item, "2099-01-01");
        context.restoreAuthSystemState();

        assertTrue("precondition: the generated zip exists", zip.exists());

        fireItemModifyMetadataEvent(item);

        assertFalse("the zip must be deleted when the item is put under embargo", zip.exists());
    }

    @Test
    public void datasetZipCreatedWhenEmbargoLifted() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        setEmbargo(item, "2099-01-01");
        File zip = new File(datasetsDir, DatashareItemDataset.getFileName(item.getHandle()));
        clearEmbargo(item);
        context.restoreAuthSystemState();

        assertFalse("precondition: an embargoed item has no zip", zip.exists());

        fireItemModifyMetadataEvent(item);

        assertTrue("the zip must be generated when the embargo is lifted", zip.exists());

        context.turnOffAuthorisationSystem();
        datasetService.deleteDatasetForItem(context, item);
        context.restoreAuthSystemState();
    }

    @Test
    public void datasetZipDeletedWhenBitstreamRestricted() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = placeDatasetZipFile(item);
        registerDatasetRecord(item);
        Bitstream bitstream = firstOriginalBitstream(item);
        // Restrict the file: it is no longer readable by Anonymous, so the public zip must go.
        restrictBitstream(bitstream);
        context.restoreAuthSystemState();

        assertTrue("precondition: the generated zip exists", zip.exists());

        fireBitstreamModifyEvent(bitstream);

        assertFalse("the zip must be deleted when a file's policy is restricted", zip.exists());
    }

    @Test
    public void datasetZipDeletedWhenBundleRestricted() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = placeDatasetZipFile(item);
        registerDatasetRecord(item);
        Bundle original = itemService.getBundles(item, "ORIGINAL").get(0);
        // Restrict the bundle itself (the bitstreams stay anonymously readable). The whole access
        // path must be public, so a restricted bundle must still drop the zip.
        authorizeService.removePoliciesActionFilter(context, original, Constants.READ);
        context.restoreAuthSystemState();

        assertTrue("precondition: the generated zip exists", zip.exists());

        fireBundleModifyEvent(original);

        assertFalse("the zip must be deleted when the bundle is restricted", zip.exists());
    }

    @Test
    public void datasetZipRegeneratedWhenBitstreamMadePublicAgain() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        Bitstream bitstream = firstOriginalBitstream(item);
        restrictBitstream(bitstream);
        File zip = new File(datasetsDir, DatashareItemDataset.getFileName(item.getHandle()));
        // Now release the file back to the public.
        makeBitstreamPublic(bitstream);
        context.restoreAuthSystemState();

        assertFalse("precondition: a restricted item has no zip", zip.exists());

        fireBitstreamModifyEvent(bitstream);

        assertTrue("the zip must be regenerated when the file is made public again", zip.exists());

        context.turnOffAuthorisationSystem();
        datasetService.deleteDatasetForItem(context, item);
        context.restoreAuthSystemState();
    }

    @Test
    public void datasetZipNotGeneratedForRestrictedItemOnInstall() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        Bitstream bitstream = firstOriginalBitstream(item);
        restrictBitstream(bitstream);
        File zip = new File(datasetsDir, DatashareItemDataset.getFileName(item.getHandle()));
        context.restoreAuthSystemState();

        fireItemInstallEvent(item);

        assertFalse("no zip may be generated for an item whose files are not public", zip.exists());
    }

    @Test
    public void datasetZipRegeneratedWhenBitstreamRemovedFromOriginalBundle() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = placeDatasetZipFile(item);
        registerDatasetRecord(item);
        Bundle original = itemService.getBundles(item, "ORIGINAL").get(0);
        context.restoreAuthSystemState();

        assertTrue("precondition: the (stale) generated zip exists", zip.exists());

        fireBundleEvent(Event.REMOVE, original);

        // The fileset changed, so the stale zip is dropped and regenerated from the current files
        // (the public item is still available), rather than left deleted until the next batch run.
        assertTrue("the zip must be regenerated when a file is removed", zip.exists());
        assertFalse("the regenerated zip must not be the stale placeholder", isStalePlaceholder(zip));

        context.turnOffAuthorisationSystem();
        datasetService.deleteDatasetForItem(context, item);
        context.restoreAuthSystemState();
    }

    @Test
    public void datasetZipRegeneratedWhenBitstreamAddedToOriginalBundle() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedItemWithFile();
        File zip = placeDatasetZipFile(item);
        registerDatasetRecord(item);
        Bundle original = itemService.getBundles(item, "ORIGINAL").get(0);
        context.restoreAuthSystemState();

        assertTrue("precondition: the (stale) generated zip exists", zip.exists());

        fireBundleEvent(Event.ADD, original);

        assertTrue("the zip must be regenerated when a file is added", zip.exists());
        assertFalse("the regenerated zip must not be the stale placeholder", isStalePlaceholder(zip));

        context.turnOffAuthorisationSystem();
        datasetService.deleteDatasetForItem(context, item);
        context.restoreAuthSystemState();
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

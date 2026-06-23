/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.BundleService;
import org.dspace.content.service.ItemService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for the DataShare "download all files" zip that drive the <em>real</em> event
 * dispatch path: each test performs the actual content mutation (uploading or removing a bitstream,
 * moving an item) as an authorized user and commits, so the events flow through the
 * {@link uk.ac.ed.datashare.event.DatashareConsumer} that is registered in the default dispatcher -
 * exactly as a REST request does. This is what reproduces
 * <a href="https://github.com/dataquest-dev/dspace-customers/issues/669">issue #669</a>: when a new
 * bitstream was uploaded to an archived item, the stale "download all" zip was left in place.
 *
 * <p>The sibling {@code DatashareDatasetConsumerIT} fires synthetic events at a hand-built consumer
 * and therefore cannot catch a regression in how the real upload flow dispatches events; these tests
 * complement it by exercising that real flow.</p>
 */
public class DatashareDatasetUploadIT extends AbstractIntegrationTestWithDatabase {

    /** Marker content written into a pre-existing zip so we can tell a stale zip from a fresh one. */
    private static final byte[] STALE_MARKER = "stale-zip-content".getBytes(StandardCharsets.UTF_8);

    private final DatashareDatasetService datasetService =
            ContentServiceFactory.getInstance().getDatashareDatasetService();
    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final BundleService bundleService = ContentServiceFactory.getInstance().getBundleService();
    private final BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    private File datasetsDir;
    private String originalDatasetsPath;

    /** Items for which a dataset DB record was registered; cleaned up before the builders tear down. */
    private final List<Item> datasetItems = new ArrayList<>();

    @Before
    public void configureDatasetsPath() throws Exception {
        originalDatasetsPath = configurationService.getProperty("datasets.path");
        datasetsDir = Files.createTempDirectory("datashare-upload").toFile();
        configurationService.setProperty("datasets.path", datasetsDir.getAbsolutePath());
    }

    @After
    public void cleanup() throws Exception {
        // Drop every dataset DB record we registered BEFORE the builder teardown deletes the items;
        // a leftover record (item_id FK) would otherwise fail item deletion and mask the real result.
        // This @After runs before AbstractIntegrationTestWithDatabase#destroy (subclass @After first).
        // The shared state (authorization + datasets.path) MUST be restored even if a delete throws,
        // otherwise it leaks into later integration tests and causes order-dependent failures.
        try {
            context.turnOffAuthorisationSystem();
            for (Item item : datasetItems) {
                datasetService.deleteDatasetForItem(context, item);
            }
        } finally {
            context.restoreAuthSystemState();
            configurationService.setProperty("datasets.path", originalDatasetsPath);
            FileUtils.deleteQuietly(datasetsDir);
        }
    }

    private Item createArchivedPublicItemWithFile() throws Exception {
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

    /** Put a stale generated zip + DB record on disk, as the ds-datasets job would have left them. */
    private File placeStaleDatasetZip(Item item) throws Exception {
        String fileName = DatashareItemDataset.getFileName(item.getHandle());
        File zip = new File(datasetsDir, fileName);
        Files.write(zip.toPath(), STALE_MARKER);
        datasetService.insertDatashareDataset(context, item, fileName, "stale-checksum");
        datasetItems.add(item);
        return zip;
    }

    /** Whether the original, stale zip file is still sitting on disk untouched. */
    private boolean staleZipStillPresent(File zip) throws Exception {
        return zip.exists() && Arrays.equals(Files.readAllBytes(zip.toPath()), STALE_MARKER);
    }

    /** Entry (file) names inside a generated dataset zip. */
    private List<String> zipEntryNames(File zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(zip.toPath())))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    /**
     * Issue #669: uploading a new bitstream to an archived, public item must invalidate the stale
     * "download all" zip (it must be deleted so the ds-datasets job regenerates it from the current
     * fileset, or regenerated immediately) - it must never be left as the old, stale file.
     */
    @Test
    public void datasetZipInvalidatedWhenBitstreamUploaded() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedPublicItemWithFile();
        Bundle original = itemService.getBundles(item, "ORIGINAL").get(0);
        File zip = placeStaleDatasetZip(item);
        context.restoreAuthSystemState();

        assertTrue("precondition: a (stale) generated zip exists", staleZipStillPresent(zip));

        // Upload a new file exactly as the REST endpoint does: authorization ENFORCED, as an
        // authorized user, then commit so the events dispatch through the registered consumer.
        context.setCurrentUser(admin);
        try (InputStream is = IOUtils.toInputStream("second file content", StandardCharsets.UTF_8)) {
            Bitstream added = bitstreamService.create(context, original, is);
            added.setName(context, "second-file.txt");
            bitstreamService.update(context, added);
        }
        itemService.update(context, item);
        context.commit();

        assertFalse("uploading a new bitstream must invalidate the stale download-all zip",
                staleZipStillPresent(zip));
        // When the zip is regenerated (rather than only deleted to await the batch job) it must
        // reflect the current fileset - i.e. include the newly uploaded file alongside the original.
        if (zip.exists()) {
            List<String> names = zipEntryNames(zip);
            assertTrue("the regenerated zip must contain the newly uploaded file: " + names,
                    names.contains("second-file.txt"));
            assertTrue("the regenerated zip must still contain the original file: " + names,
                    names.contains("dataset-file.txt"));
        }
    }

    /**
     * Removing a bitstream from an archived item must <em>regenerate</em> the "download all" zip so
     * the removed file disappears from it - just like adding one regenerates it. The REST API deletes
     * a bitstream by removing it from its bundle via {@code bundleService.removeBitstream} (see
     * {@code BitstreamRestRepository#delete}), which fires the Bundle REMOVE event this relies on.
     */
    @Test
    public void datasetZipRegeneratedWhenBitstreamRemoved() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedPublicItemWithFile(); // dataset-file.txt
        Bundle original = itemService.getBundles(item, "ORIGINAL").get(0);
        // Add a second file so something remains after the removal.
        try (InputStream is = IOUtils.toInputStream("second file content", StandardCharsets.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("second-file.txt").withMimeType("text/plain").build();
        }
        Bitstream toRemove = original.getBitstreams().stream()
                .filter(b -> "dataset-file.txt".equals(b.getName())).findFirst().orElseThrow();
        File zip = placeStaleDatasetZip(item);
        context.restoreAuthSystemState();

        assertTrue("precondition: a (stale) generated zip exists", staleZipStillPresent(zip));

        context.setCurrentUser(admin);
        bundleService.removeBitstream(context, original, toRemove);
        itemService.update(context, item);
        context.commit();

        assertFalse("removing a bitstream must invalidate the stale download-all zip",
                staleZipStillPresent(zip));
        // It must be regenerated (not merely deleted) so the zip reflects the current fileset.
        assertTrue("the download-all zip must be regenerated after a removal", zip.exists());
        List<String> names = zipEntryNames(zip);
        assertTrue("the regenerated zip must keep the remaining file: " + names,
                names.contains("second-file.txt"));
        assertFalse("the regenerated zip must not contain the removed file: " + names,
                names.contains("dataset-file.txt"));
    }

    /**
     * Renaming a file (a metadata-only change to a bitstream in a zip bundle) changes the entry name
     * inside the "download all" zip, so the stale zip must be invalidated and regenerated.
     */
    @Test
    public void datasetZipInvalidatedWhenBitstreamRenamed() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedPublicItemWithFile();
        Bundle original = itemService.getBundles(item, "ORIGINAL").get(0);
        Bitstream existing = original.getBitstreams().get(0);
        File zip = placeStaleDatasetZip(item);
        context.restoreAuthSystemState();

        assertTrue("precondition: a (stale) generated zip exists", staleZipStillPresent(zip));

        context.setCurrentUser(admin);
        existing.setName(context, "renamed-file.txt");
        bitstreamService.update(context, existing);
        context.commit();

        assertFalse("renaming a file must invalidate the stale download-all zip (its entry name changed)",
                staleZipStillPresent(zip));
        // The regenerated zip must use the new file name, never the old one.
        if (zip.exists()) {
            List<String> names = zipEntryNames(zip);
            assertTrue("the regenerated zip must contain the renamed file: " + names,
                    names.contains("renamed-file.txt"));
            assertFalse("the regenerated zip must not contain the old file name: " + names,
                    names.contains("dataset-file.txt"));
        }
    }

    /**
     * The DSpace REST API renames a file with an in-place value replace
     * ("replace /metadata/dc.title/0/value"): it edits the existing metadata value object directly
     * rather than adding/removing metadata, so the resulting Bitstream MODIFY_METADATA event carries
     * a <em>null</em> detail. The zip must still be regenerated. This guards against a fix that only
     * reacts when the event detail explicitly names the title field (a value-replace never does).
     */
    @Test
    public void datasetZipInvalidatedWhenBitstreamRenamedInPlace() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedPublicItemWithFile();
        Bundle original = itemService.getBundles(item, "ORIGINAL").get(0);
        Bitstream existing = original.getBitstreams().get(0);
        File zip = placeStaleDatasetZip(item);
        context.restoreAuthSystemState();

        assertTrue("precondition: a (stale) generated zip exists", staleZipStillPresent(zip));

        context.setCurrentUser(admin);
        // Mirror DSpaceObjectMetadataReplaceOperation#replaceSingleMetadataValue: edit the existing
        // dc.title value object in place, then flag the bitstream's metadata as modified.
        List<MetadataValue> titles = bitstreamService.getMetadata(existing, "dc", "title", null, Item.ANY);
        titles.get(0).setValue("renamed-inplace.txt");
        bitstreamService.setMetadataModified(existing);
        bitstreamService.update(context, existing);
        context.commit();

        assertFalse("an in-place file rename (REST value replace) must invalidate the stale zip",
                staleZipStillPresent(zip));
        if (zip.exists()) {
            List<String> names = zipEntryNames(zip);
            assertTrue("the regenerated zip must contain the in-place renamed file: " + names,
                    names.contains("renamed-inplace.txt"));
        }
    }

    /**
     * Moving an archived, public item to another collection must not silently strip its
     * "download all" zip nor leave a stale one: the item is still public, so a fresh zip must
     * remain available.
     */
    @Test
    public void datasetZipPreservedWhenItemMovedBetweenCollections() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedPublicItemWithFile();
        Collection from = item.getOwningCollection();
        Collection to = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 2").build();
        File zip = placeStaleDatasetZip(item);
        context.restoreAuthSystemState();

        assertTrue("precondition: a generated zip exists", zip.exists());

        context.setCurrentUser(admin);
        itemService.move(context, item, from, to);
        context.commit();

        assertTrue("moving a public item between collections must keep its download-all zip available",
                zip.exists());
        assertFalse("the zip kept after a move must be a fresh one, not the stale pre-move file",
                staleZipStillPresent(zip));
    }

    /**
     * Two bitstreams in an item can share a name (e.g. the same file uploaded twice). The zip names
     * each entry after the bitstream's dc.title, and a {@link java.util.zip.ZipOutputStream} rejects a
     * duplicate entry name with a ZipException - which previously aborted the whole zip, leaving the
     * item with no "download all" zip at all. The zip must still be generated, with the duplicate
     * name disambiguated.
     */
    @Test
    public void datasetZipGeneratedDespiteDuplicateFileNames() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createArchivedPublicItemWithFile(); // dataset-file.txt
        // A second file with the SAME name as the first.
        try (InputStream is = IOUtils.toInputStream("duplicate content", StandardCharsets.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("dataset-file.txt").withMimeType("text/plain").build();
        }
        File zip = new File(datasetsDir, DatashareItemDataset.getFileName(item.getHandle()));
        datasetItems.add(item);
        datasetService.createDatasetForItem(context, item);
        context.restoreAuthSystemState();

        assertTrue("the zip must be generated even when two files share a name", zip.exists());
        List<String> names = zipEntryNames(zip);
        assertEquals("both same-named files must be in the zip (one disambiguated): " + names,
                2, names.stream().filter(n -> n.startsWith("dataset-file")).count());
        assertTrue("the zip keeps the original name: " + names, names.contains("dataset-file.txt"));
        assertTrue("the duplicate is disambiguated: " + names, names.contains("dataset-file (1).txt"));
    }
}

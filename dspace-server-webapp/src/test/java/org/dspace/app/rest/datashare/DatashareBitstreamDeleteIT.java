/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.datashare;

import static org.dspace.app.rest.repository.patch.operation.BitstreamRemoveOperation.OPERATION_PATH_BITSTREAM_REMOVE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.RemoveOperation;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.datashare.DatashareItemDataset;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that deleting a file through the path the Angular UI uses - a bulk
 * {@code PATCH /api/core/bitstreams} with {@code remove} operations
 * ({@link org.dspace.app.rest.repository.patch.operation.BitstreamRemoveOperation}) - regenerates the
 * DataShare "download all files" zip so the removed file disappears from it.
 *
 * <p>This guards the gap where that bulk path deleted the bitstream silently (no Bundle REMOVE event),
 * leaving the stale zip in place even though the single-resource {@code DELETE} endpoint was fixed.</p>
 */
public class DatashareBitstreamDeleteIT extends AbstractControllerIntegrationTest {

    private static final byte[] STALE_MARKER = "stale-zip-content".getBytes(StandardCharsets.UTF_8);

    private final DatashareDatasetService datasetService =
            ContentServiceFactory.getInstance().getDatashareDatasetService();
    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    private File datasetsDir;
    private String originalDatasetsPath;
    private Item datasetItem;

    @Before
    public void enableDatasetZip() throws Exception {
        originalDatasetsPath = configurationService.getProperty("datasets.path");
        datasetsDir = Files.createTempDirectory("datashare-bitstream-delete").toFile();
        configurationService.setProperty("datasets.path", datasetsDir.getAbsolutePath());
    }

    @After
    public void cleanup() throws Exception {
        try {
            if (datasetItem != null) {
                context.turnOffAuthorisationSystem();
                datasetService.deleteDatasetForItem(context, datasetItem);
                context.restoreAuthSystemState();
            }
        } finally {
            configurationService.setProperty("datasets.path", originalDatasetsPath);
            FileUtils.deleteQuietly(datasetsDir);
        }
    }

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

    @Test
    public void bulkPatchDeleteRegeneratesDatasetZipWithoutRemovedFile() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();
        datasetItem = ItemBuilder.createItem(context, collection)
                .withTitle("Datashare item").withIssueDate("2024-01-17").build();
        Bitstream fileA;
        try (InputStream is = IOUtils.toInputStream("file a content", StandardCharsets.UTF_8)) {
            fileA = BitstreamBuilder.createBitstream(context, datasetItem, is)
                    .withName("file-a.txt").withMimeType("text/plain").build();
        }
        try (InputStream is = IOUtils.toInputStream("file b content", StandardCharsets.UTF_8)) {
            BitstreamBuilder.createBitstream(context, datasetItem, is)
                    .withName("file-b.txt").withMimeType("text/plain").build();
        }
        // Put a stale generated zip + DB record on disk, as the ds-datasets job would have left them.
        String fileName = DatashareItemDataset.getFileName(datasetItem.getHandle());
        File zip = new File(datasetsDir, fileName);
        Files.write(zip.toPath(), STALE_MARKER);
        datasetService.insertDatashareDataset(context, datasetItem, fileName, "stale-checksum");
        context.restoreAuthSystemState();

        assertTrue("precondition: a (stale) generated zip exists", zip.exists());

        // Delete file-a via the bulk PATCH the Angular UI uses.
        List<Operation> ops = new ArrayList<>();
        ops.add(new RemoveOperation(OPERATION_PATH_BITSTREAM_REMOVE + fileA.getID()));
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(patch("/api/core/bitstreams")
                        .content(getPatchContent(ops))
                        .contentType("application/json-patch+json"))
                .andExpect(status().isNoContent());

        // The zip must have been regenerated from the current fileset: file-a gone, file-b kept.
        assertTrue("the download-all zip must still exist (regenerated) after the delete", zip.exists());
        List<String> names = zipEntryNames(zip);
        assertTrue("the regenerated zip must keep the remaining file: " + names, names.contains("file-b.txt"));
        assertFalse("the regenerated zip must not contain the deleted file: " + names,
                names.contains("file-a.txt"));
    }
}

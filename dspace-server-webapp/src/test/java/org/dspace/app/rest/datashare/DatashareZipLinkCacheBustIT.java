/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.datashare;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end regression test for the "download all files" (dataset zip) cache-busting fix.
 *
 * <p>Reproduces the reported bug: after a user uploads a new file, the zip <em>is</em> regenerated
 * server-side, but clicking "Download all files" in the same browser keeps downloading the previous
 * zip - because the download link the {@code zip-file-link} endpoint returns is a static-file URL
 * derived only from the item handle and was byte-identical across regenerations, so the browser
 * served its cached copy (a different browser, with an empty cache, downloaded the new zip). The fix
 * appends a content-version token ({@code ?v=<checksum>}) so the URL changes whenever the zip content
 * changes, forcing the browser to refetch.</p>
 *
 * <p>These tests drive the real {@link DatashareDatasetRestController} endpoint against a real,
 * physically-generated zip (checksum stored in the dataset row), mirroring the datashare instance's
 * HTTP behaviour.</p>
 */
public class DatashareZipLinkCacheBustIT extends AbstractControllerIntegrationTest {

    private static final String ZIP_FILE_LINK = "/api/datashare/items/%s/zip-file-link";
    /** A dataset zip URL ending in a 32-char md5 cache-busting version, e.g. .../DS_1_2.zip?v=<md5>. */
    private static final String VERSIONED_ZIP_URL = ".*/DS_\\d+_\\d+\\.zip\\?v=[0-9a-f]{32}";

    private final DatashareDatasetService datasetService =
            ContentServiceFactory.getInstance().getDatashareDatasetService();
    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    private File datasetsDir;
    private String originalDatasetsPath;
    private String originalDownloadUrl;
    private Item datasetItem;

    @Before
    public void enableDatasetZip() throws Exception {
        originalDatasetsPath = configurationService.getProperty("datasets.path");
        originalDownloadUrl = configurationService.getProperty("datashare.download.zip.url");
        datasetsDir = Files.createTempDirectory("datashare-zip-link").toFile();
        configurationService.setProperty("datasets.path", datasetsDir.getAbsolutePath());
        configurationService.setProperty("datashare.download.zip.url", "http://localhost:8080/download");
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
            configurationService.setProperty("datashare.download.zip.url", originalDownloadUrl);
            FileUtils.deleteQuietly(datasetsDir);
        }
    }

    /** Build an archived, anonymously-readable item with a single file and generate its dataset zip. */
    private Item archivedItemWithGeneratedZip() throws Exception {
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();
        datasetItem = ItemBuilder.createItem(context, collection)
                .withTitle("Datashare item").withIssueDate("2024-01-17").build();
        try (InputStream is = IOUtils.toInputStream("first file content", StandardCharsets.UTF_8)) {
            BitstreamBuilder.createBitstream(context, datasetItem, is)
                    .withName("file-a.txt").withMimeType("text/plain").build();
        }
        // Generate the physical zip + store its md5 checksum, exactly as the ds-datasets job / event
        // consumer does on archive.
        datasetService.createDatasetForItem(context, datasetItem);
        return datasetItem;
    }

    private String fetchLink(Item item) throws Exception {
        return getClient().perform(get(String.format(ZIP_FILE_LINK, item.getID())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    public void zipFileLinkCarriesContentVersion() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = archivedItemWithGeneratedZip();
        context.restoreAuthSystemState();

        String link = fetchLink(item);

        assertTrue("the download link must carry a ?v=<checksum> cache-busting token, was: " + link,
                link.matches(VERSIONED_ZIP_URL));
    }

    @Test
    public void zipFileLinkVersionChangesAfterRegeneration() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = archivedItemWithGeneratedZip();
        context.restoreAuthSystemState();

        String before = fetchLink(item);
        assertTrue("precondition: the initial link is versioned, was: " + before,
                before.matches(VERSIONED_ZIP_URL));

        // Upload a new file and regenerate the zip - the customer's exact action.
        context.turnOffAuthorisationSystem();
        try (InputStream is = IOUtils.toInputStream("second file content", StandardCharsets.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("file-b.txt").withMimeType("text/plain").build();
        }
        datasetService.createDatasetForItem(context, item);
        context.restoreAuthSystemState();

        String after = fetchLink(item);
        assertTrue("the regenerated link is versioned, was: " + after, after.matches(VERSIONED_ZIP_URL));
        // The whole point: regenerating the zip must change the link so the browser refetches instead
        // of serving the previously-cached (stale) zip.
        assertNotEquals("uploading a new file must change the download link (cache-busting)", before, after);
    }
}

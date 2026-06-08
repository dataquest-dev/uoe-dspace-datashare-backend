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

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.InstallItemService;
import org.dspace.content.service.ItemService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test for the DataShare "download all" zip generation on archive.
 *
 * <p>When {@code datasets.path} is configured, archiving an item makes the {@code datashare} event
 * consumer generate the zip synchronously, in the same transaction as the archival. The zip
 * readability check ({@link org.dspace.content.datashare.DatashareItemDataset}) must not disturb that
 * transaction. A regression there created a second {@link org.dspace.core.Context} and aborted it;
 * because DSpace binds a single Hibernate session per thread, that closed the session shared with the
 * archival transaction, detached the item, threw a {@code LazyInitializationException} while building
 * the zip and rolled the whole archive back -- leaving the item stuck in the workflow (no handle, no
 * owning collection, transforms not persisted). This installs an item with a file and
 * {@code datasets.path} set and asserts the archive still completes and its archive-time transforms
 * are persisted.</p>
 */
public class DatashareZipOnArchiveIT extends AbstractIntegrationTestWithDatabase {

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final InstallItemService installItemService =
            ContentServiceFactory.getInstance().getInstallItemService();
    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    private String previousDatasetsPath;

    @Before
    public void enableDatasetZip() {
        previousDatasetsPath = configurationService.getProperty("datasets.path");
        File datasetsDir = new File(System.getProperty("java.io.tmpdir"), "datashare-it-datasets");
        datasetsDir.mkdirs();
        configurationService.setProperty("datasets.path", datasetsDir.getAbsolutePath());
    }

    @After
    public void restoreDatasetsPath() {
        configurationService.setProperty("datasets.path", previousDatasetsPath);
    }

    private List<String> values(Item item, String field) {
        return itemService.getMetadataByMetadataString(item, field).stream()
                .map(MetadataValue::getValue).collect(Collectors.toList());
    }

    @Test
    public void archiveWithSynchronousZipGenerationDoesNotRollBack() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();
        WorkspaceItem wsi = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("Datashare zip-on-archive item").withIssueDate("2024-01-17").build();
        Item item = wsi.getItem();
        // Submission-time fields, so we also prove the transforms survive the zip generation.
        itemService.addMetadata(context, item, "dc", "subject", "ddc", null, "GB");
        itemService.addMetadata(context, item, "dc", "coverage", "spatial", null, "Edinburgh");
        try (InputStream is = IOUtils.toInputStream("hello world", StandardCharsets.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is).withName("file.txt").build();
        }
        itemService.update(context, item);

        // Real archive: install fires Item+Install; dispatchEvents runs the registered datashare
        // consumer, which - because datasets.path is set - generates the zip synchronously. The
        // readability check runs here with authorization turned off (ignoreAuthorization), the path
        // that previously created and aborted a second Context and broke this transaction.
        Item installed = installItemService.installItem(context, wsi);
        context.dispatchEvents();
        context.restoreAuthSystemState();

        // The archive must have completed - not been rolled back by zip generation.
        assertTrue("item must remain archived (not rolled back by zip generation)", installed.isArchived());
        // Reading metadata here must not throw LazyInitializationException (the caller's session is intact)
        // and the archive-time transforms must be persisted.
        assertEquals("exactly one dc.date.available", 1, values(installed, "dc.date.available").size());
        assertTrue("dc.subject.ddc cleared on archive", values(installed, "dc.subject.ddc").isEmpty());
        assertTrue("country merged into dc.coverage.spatial", values(installed, "dc.coverage.spatial").contains("GB"));
    }
}

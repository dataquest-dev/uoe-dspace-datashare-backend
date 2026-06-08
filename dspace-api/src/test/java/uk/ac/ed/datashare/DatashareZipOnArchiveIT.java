/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.datashare.DatashareItemDataset;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.core.Constants;
import org.dspace.eperson.Group;
import org.dspace.event.Event;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.ac.ed.datashare.event.DatashareConsumer;

/**
 * Regression test for the deployed-instance archive rollback.
 *
 * <p>When {@code datasets.path} is set, archiving generates the "download all" zip synchronously in
 * the archival transaction. The zip readability check ({@link DatashareItemDataset}) must not create
 * and abort a second {@link org.dspace.core.Context}: DSpace binds one Hibernate session per thread,
 * so that would close the session shared with the archival transaction, detach the item, throw a
 * {@code LazyInitializationException} during zip generation and roll the whole archive back. The
 * trigger on the deployed instance is a request context that carries special groups (e.g.
 * {@code DATASHARE_USERS}). This installs a public item, puts a special group on the
 * authorization-enforcing context (as a request does) and asserts the zip is still generated - it
 * would not be if the readability check had closed the session.</p>
 */
public class DatashareZipOnArchiveIT extends AbstractIntegrationTestWithDatabase {

    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();
    private final DatashareDatasetService datasetService =
            ContentServiceFactory.getInstance().getDatashareDatasetService();

    private File datasetsDir;
    private String originalDatasetsPath;

    @Before
    public void enableDatasetZip() throws Exception {
        originalDatasetsPath = configurationService.getProperty("datasets.path");
        datasetsDir = Files.createTempDirectory("datashare-zip-on-archive").toFile();
        configurationService.setProperty("datasets.path", datasetsDir.getAbsolutePath());
    }

    @After
    public void restoreDatasetsPath() {
        configurationService.setProperty("datasets.path", originalDatasetsPath);
        FileUtils.deleteQuietly(datasetsDir);
    }

    /** Fire the event raised when a new item is installed (archived), as the submission flow does. */
    private void fireItemInstallEvent(Item item) throws Exception {
        DatashareConsumer consumer = new DatashareConsumer();
        consumer.initialize();
        Event event = new Event(Event.INSTALL, Constants.ITEM, item.getID(), item.getHandle());
        consumer.consume(context, event);
        consumer.end(context);
    }

    @Test
    public void zipGeneratedWhenArchivingContextCarriesSpecialGroups() throws Exception {
        context.turnOffAuthorisationSystem();
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
        Group special = GroupBuilder.createGroup(context).withName("DATASHARE_USERS_TEST").build();
        context.restoreAuthSystemState();

        File zip = new File(datasetsDir, DatashareItemDataset.getFileName(item.getHandle()));
        assertFalse("precondition: no zip exists yet", zip.exists());

        // Reproduce the deployed-instance trigger: an authorization-enforcing request context that
        // carries a special group. The readability check must evaluate anonymous access without
        // creating/aborting a second Context (which would close the shared archival session).
        context.setSpecialGroup(special.getID());
        try {
            fireItemInstallEvent(item);

            assertTrue("the zip must be generated even when the context carries special groups",
                    zip.exists());
        } finally {
            // The create path registers a dataset DB record (item_id FK). Always drop it - even if
            // the assertion above fails - so the builder teardown can delete the item; otherwise a
            // foreign-key violation during cleanup would mask the real test failure.
            context.turnOffAuthorisationSystem();
            datasetService.deleteDatasetForItem(context, item);
            context.restoreAuthSystemState();
        }
    }
}

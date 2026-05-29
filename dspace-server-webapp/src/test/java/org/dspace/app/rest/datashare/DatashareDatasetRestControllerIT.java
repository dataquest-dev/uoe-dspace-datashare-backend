/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.datashare;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.junit.Test;

/**
 * Integration tests for {@link DatashareDatasetRestController}.
 * <p>
 * These verify the authorization fix described in
 * https://github.com/dataquest-dev/dspace-customers/issues/647: the zip-file-link endpoint
 * must not expose a download link (the "Download all files" feature) to users who are not
 * authorized to read the underlying files.
 */
public class DatashareDatasetRestControllerIT extends AbstractControllerIntegrationTest {

    private static final String ZIP_FILE_LINK = "/api/datashare/items/%s/zip-file-link";
    private static final String ZIP_FILE_DOWNLOADABLE = "/api/datashare/items/%s/zip-file-downloadable";

    /**
     * Build an item with a single bitstream. When {@code readerGroup} is not null the bitstream
     * is restricted so that only members of that group (and administrators) may read it.
     */
    private Item buildItemWithBitstream(Group readerGroup) throws Exception {
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1")
                .build();

        Item item = ItemBuilder.createItem(context, col1)
                .withTitle("Datashare item")
                .withIssueDate("2024-01-17")
                .withAuthor("Doe, John")
                .build();

        String bitstreamContent = "Dataset file content";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, StandardCharsets.UTF_8)) {
            BitstreamBuilder bb = BitstreamBuilder
                    .createBitstream(context, item, is)
                    .withName("dataset-file.txt")
                    .withMimeType("text/plain");
            if (readerGroup != null) {
                bb = bb.withReaderGroup(readerGroup);
            }
            bb.build();
        }
        return item;
    }

    @Test
    public void zipFileLinkForbiddenForAnonymousWhenFilesAreRestricted() throws Exception {
        context.turnOffAuthorisationSystem();
        // Restrict the file so that only administrators (no anonymous, no regular users) can read it
        Group restrictedGroup = GroupBuilder.createGroup(context)
                .withName("Restricted Group")
                .build();
        Item item = buildItemWithBitstream(restrictedGroup);
        context.restoreAuthSystemState();

        // Anonymous users are not authenticated: the link must not be exposed (401)
        getClient().perform(get(String.format(ZIP_FILE_LINK, item.getID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void zipFileLinkForbiddenForUnauthorizedEPerson() throws Exception {
        context.turnOffAuthorisationSystem();
        Group restrictedGroup = GroupBuilder.createGroup(context)
                .withName("Restricted Group")
                .build();
        Item item = buildItemWithBitstream(restrictedGroup);
        EPerson outsider = EPersonBuilder.createEPerson(context)
                .withEmail("outsider@example.com")
                .withPassword(password)
                .build();
        context.restoreAuthSystemState();

        // An authenticated but unauthorized user must be forbidden (403)
        String token = getAuthToken(outsider.getEmail(), password);
        getClient(token).perform(get(String.format(ZIP_FILE_LINK, item.getID())))
                .andExpect(status().isForbidden());
    }

    @Test
    public void zipFileLinkAccessibleForAdminWhenFilesAreRestricted() throws Exception {
        context.turnOffAuthorisationSystem();
        Group restrictedGroup = GroupBuilder.createGroup(context)
                .withName("Restricted Group")
                .build();
        Item item = buildItemWithBitstream(restrictedGroup);
        context.restoreAuthSystemState();

        // Administrators bypass resource policies, so the request is authorized (200)
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get(String.format(ZIP_FILE_LINK, item.getID())))
                .andExpect(status().isOk());
    }

    @Test
    public void zipFileLinkAccessibleForAnonymousWhenFilesArePublic() throws Exception {
        context.turnOffAuthorisationSystem();
        // No reader group -> default anonymous read access
        Item item = buildItemWithBitstream(null);
        context.restoreAuthSystemState();

        getClient().perform(get(String.format(ZIP_FILE_LINK, item.getID())))
                .andExpect(status().isOk());
    }

    @Test
    public void zipFileDownloadableIsFalseForAnonymousWhenFilesAreRestricted() throws Exception {
        context.turnOffAuthorisationSystem();
        Group restrictedGroup = GroupBuilder.createGroup(context)
                .withName("Restricted Group")
                .build();
        Item item = buildItemWithBitstream(restrictedGroup);
        context.restoreAuthSystemState();

        // The button-visibility endpoint must report the dataset as not downloadable
        getClient().perform(get(String.format(ZIP_FILE_DOWNLOADABLE, item.getID())))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }
}

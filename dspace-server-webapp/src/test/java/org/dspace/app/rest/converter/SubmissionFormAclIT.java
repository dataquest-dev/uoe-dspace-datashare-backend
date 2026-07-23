/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.converter;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.junit.Test;

/**
 * Guards the {@code <acl>} field-hiding performed by {@link SubmissionFormConverter} against the real
 * {@code /api/config/submissionforms/*} endpoint.
 * <p>
 * The upload-from-path field is the only ACL-guarded input in the shipped configuration and its
 * "administrators only" promise is the headline of that feature, so it is exercised here end to end:
 * every other test of the feature drives the PATCH path, where the field is already present on the
 * item and the form payload is never consulted. Without these tests the guard in
 * {@code SubmissionFormConverter.getPage} could be deleted with the whole suite still green.
 * <p>
 * {@code traditionalpagetwo} is the form carrying the field in the integration-test configuration
 * ({@code dspace-api/src/test/data/dspaceFolder/config/submission-forms.xml}); the field is the last
 * of its five rows.
 */
public class SubmissionFormAclIT extends AbstractControllerIntegrationTest {

    private static final String FORM = "/api/config/submissionforms/traditionalpagetwo";

    /** The ACL-guarded input: {@code policy=deny,action=read,grantee-type=user,grantee-id=*}. */
    private static final String GUARDED_FIELD = "local.bitstream.redirectToURL";

    /** Row index of the guarded field, and of an ordinary field that no ACL touches. */
    private static final int GUARDED_ROW = 4;
    private static final int UNGUARDED_ROW = 1;

    private static final String ROW_FIELDS = "$.rows[%d].fields";
    private static final String ROW_FIELD_METADATA = "$.rows[%d].fields[0].selectableMetadata[0].metadata";

    @Test
    public void siteAdministratorSeesTheGuardedField() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get(FORM))
                .andExpect(status().isOk())
                .andExpect(jsonPath(String.format(ROW_FIELDS, GUARDED_ROW), hasSize(1)))
                .andExpect(jsonPath(String.format(ROW_FIELD_METADATA, GUARDED_ROW), is(GUARDED_FIELD)));
    }

    /**
     * The refusal that matters: an ordinary authenticated user must not learn that the field exists,
     * because the Angular form is built entirely from this payload.
     */
    @Test
    public void ordinaryUserDoesNotSeeTheGuardedField() throws Exception {
        String epersonToken = getAuthToken(eperson.getEmail(), password);

        getClient(epersonToken).perform(get(FORM))
                .andExpect(status().isOk())
                // The row itself survives, empty - the client tolerates that, and asserting it here
                // pins the shape the Angular row parser was verified against.
                .andExpect(jsonPath(String.format(ROW_FIELDS, GUARDED_ROW), hasSize(0)))
                .andExpect(content().string(not(containsString(GUARDED_FIELD))));
    }

    /**
     * {@code ACL} bypasses only {@code Group.ADMIN}, so "administrator" here means a SITE
     * administrator. A collection administrator is an ordinary user as far as this field is concerned.
     */
    @Test
    public void collectionAdministratorDoesNotSeeTheGuardedField() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1")
                .withAdminGroup(eperson)
                .build();
        context.restoreAuthSystemState();

        String collectionAdminToken = getAuthToken(eperson.getEmail(), password);

        getClient(collectionAdminToken).perform(get(FORM))
                .andExpect(status().isOk())
                .andExpect(jsonPath(String.format(ROW_FIELDS, GUARDED_ROW), hasSize(0)))
                .andExpect(content().string(not(containsString(GUARDED_FIELD))));
    }

    /**
     * The guard must hide the one field it is aimed at and nothing else, otherwise a mistake in it
     * would empty the form for every non-administrator without any test noticing.
     */
    @Test
    public void unguardedFieldsAreUnaffected() throws Exception {
        String epersonToken = getAuthToken(eperson.getEmail(), password);

        getClient(epersonToken).perform(get(FORM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(5)))
                .andExpect(jsonPath(String.format(ROW_FIELDS, UNGUARDED_ROW), hasSize(1)))
                .andExpect(jsonPath(String.format(ROW_FIELD_METADATA, UNGUARDED_ROW),
                        is("dc.description.abstract")));
    }
}

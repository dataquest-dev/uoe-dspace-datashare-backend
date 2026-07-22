/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static com.jayway.jsonpath.JsonPath.read;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.MediaType;
import org.apache.commons.io.FileUtils;
import org.dspace.app.rest.model.patch.AddOperation;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.RemoveOperation;
import org.dspace.app.rest.model.patch.ReplaceOperation;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.ClaimedTaskBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.service.ItemService;
import org.dspace.core.Utils;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.storage.bitstore.DSBitStoreService;
import org.dspace.xmlworkflow.storedcomponents.ClaimedTask;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Guards the "ingest a bitstream from an absolute server path" feature driven by the
 * {@code local.bitstream.redirectToURL} metadata field.
 * <p>
 * Two of these tests are regression tests for reported defects: the ingest must also work while an
 * item is in the review workflow, and a reviewer who is not a site administrator must never be able
 * to use it, because {@code WorkflowItemRestRepository.patch()} carries no {@code @PreAuthorize}.
 * <p>
 * The rest cover what the feature promises when something goes wrong: a failed save must roll back
 * with the pointer intact rather than commit a 200 with nothing ingested, only a site administrator
 * may write the field while anyone may clear it, the caps and the optional delete of the source file
 * behave as configured, and what lands in the assetstore is the file that was named, byte for byte.
 * The {@code <acl>} that hides the field from the submission form is covered separately, by
 * {@code org.dspace.app.rest.converter.SubmissionFormAclIT}.
 */
public class UploadFromPathIT extends AbstractControllerIntegrationTest {

    private static final String MD_FIELD = "local.bitstream.redirectToURL";
    private static final String CFG_ENABLED = "bitstream.upload-from-path.enabled";
    private static final String CFG_ALLOWED_PATHS = "bitstream.upload-from-path.allowed-paths";
    private static final String CFG_DELETE_AFTER = "bitstream.upload-from-path.delete-after-upload";
    private static final String CFG_MAX_BYTES = "bitstream.upload-from-path.max-bytes";

    /** Patch path of the field, in the form that carries it in the integration-test configuration. */
    private static final String PENDING_PATH_OP = "/sections/traditionalpagetwo/" + MD_FIELD;
    /** The same field as it appears in a submission payload. */
    private static final String PENDING_PATH_JSON = "$.sections.traditionalpagetwo['" + MD_FIELD + "']";

    private static final String FILE_NAME = "ingest-me.bin";
    private static final byte[] FILE_CONTENT =
            "content staged on the server, never uploaded through the browser".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private ItemService itemService;

    private Path allowedRoot;
    private Path outsideRoot;
    private Path sourceFile;
    private String originalEnabled;
    private String[] originalAllowedPaths;
    private String originalDeleteAfter;
    private String originalMaxBytes;
    /** Set only by {@link #breakAssetstore}, so that {@link #restoreUploadFromPath} can always undo it. */
    private DSBitStoreService assetstore;
    private File originalAssetstoreBaseDir;

    @Before
    public void enableUploadFromPath() throws Exception {
        originalEnabled = configurationService.getProperty(CFG_ENABLED);
        originalAllowedPaths = configurationService.getArrayProperty(CFG_ALLOWED_PATHS);
        originalDeleteAfter = configurationService.getProperty(CFG_DELETE_AFTER);
        originalMaxBytes = configurationService.getProperty(CFG_MAX_BYTES);

        // Canonicalised up front so the paths the tests assert on are the ones the validator resolves to,
        // whatever the CI worker's temp directory is a symlink to.
        allowedRoot = Files.createTempDirectory("upload-from-path-allowed").toRealPath();
        outsideRoot = Files.createTempDirectory("upload-from-path-outside").toRealPath();
        sourceFile = Files.write(allowedRoot.resolve(FILE_NAME), FILE_CONTENT);

        configurationService.setProperty(CFG_ENABLED, true);
        configurationService.setProperty(CFG_ALLOWED_PATHS, allowedRoot.toString());
    }

    @After
    public void restoreUploadFromPath() {
        configurationService.setProperty(CFG_ENABLED, originalEnabled);
        configurationService.setProperty(CFG_ALLOWED_PATHS, null);
        for (String root : originalAllowedPaths) {
            configurationService.addPropertyValue(CFG_ALLOWED_PATHS, root);
        }
        configurationService.setProperty(CFG_DELETE_AFTER, originalDeleteAfter);
        configurationService.setProperty(CFG_MAX_BYTES, originalMaxBytes);
        if (assetstore != null) {
            assetstore.setBaseDir(originalAssetstoreBaseDir);
        }
        if (allowedRoot != null) {
            FileUtils.deleteQuietly(allowedRoot.toFile());
        }
        if (outsideRoot != null) {
            FileUtils.deleteQuietly(outsideRoot.toFile());
        }
    }

    /** The happy path on a submission: the file lands in the upload section and the pointer is cleared. */
    @Test
    public void ingestsFileIntoWorkspaceItemAsAdmin() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Ingested during submission"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)))
                .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value", is(FILE_NAME)))
                .andExpect(jsonPath("$.sections.upload.files[0].sizeBytes", is(FILE_CONTENT.length)));

        Item item = context.reloadEntity(witem).getItem();
        assertTrue("the pending path must be cleared so the ingest cannot repeat",
                itemService.getMetadataByMetadataString(item, MD_FIELD).isEmpty());
        assertTrue("the ingest must be recorded in the provenance",
                itemService.getMetadataByMetadataString(item, "dc.description.provenance").stream()
                        .anyMatch(mdv -> mdv.getValue().contains(sourceFile.toString())
                                && mdv.getValue().contains(admin.getEmail())));
    }

    /** A second save must not re-ingest: the pointer was cleared inside the first transaction. */
    @Test
    public void secondSaveDoesNotCreateDuplicateBitstream() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("First save"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)));

        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Second save"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)));
    }

    /**
     * The reported gap: the ingest must also work on an item that has already left the submission and
     * is being edited in the review workflow.
     */
    @Test
    public void ingestsFileIntoWorkflowItemAsAdmin() throws Exception {
        context.turnOffAuthorisationSystem();
        XmlWorkflowItem wfitem = claimedWorkflowItem(admin);
        setPendingPath(wfitem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/workflow/workflowitems/" + wfitem.getID())
                        .content(replaceTitlePatch("Ingested during review"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)))
                .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value", is(FILE_NAME)))
                .andExpect(jsonPath("$.sections.upload.files[0].sizeBytes", is(FILE_CONTENT.length)));

        Item item = context.reloadEntity(wfitem).getItem();
        assertTrue("the pending path must be cleared so the ingest cannot repeat",
                itemService.getMetadataByMetadataString(item, MD_FIELD).isEmpty());
    }

    /**
     * The same ingest at the last step of the shipped workflow, which the endpoint reaches by a different
     * action: {@code FinalEditAction} offers {@code submit_edit_metadata} just as the edit step's
     * {@code AcceptEditRejectAction} does, so the guard in {@code checkIfEditMetadataAllowedInCurrentStep}
     * lets the save through there too. {@code ReviewAction} does not offer it, so the first step of the
     * workflow is out of reach by design and is not covered here.
     */
    @Test
    public void ingestsFileIntoWorkflowItemAtTheFinalEditStep() throws Exception {
        context.turnOffAuthorisationSystem();
        XmlWorkflowItem wfitem = claimedFinalEditWorkflowItem(admin);
        setPendingPath(wfitem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/workflow/workflowitems/" + wfitem.getID())
                        .content(replaceTitlePatch("Ingested during the final edit"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)))
                .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value", is(FILE_NAME)))
                .andExpect(jsonPath("$.sections.upload.files[0].sizeBytes", is(FILE_CONTENT.length)))
                .andExpect(jsonPath("$.sections.upload.files[0].checkSum.value", is(Utils.getMD5(FILE_CONTENT))))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Ingested during the final edit")));

        Item item = context.reloadEntity(wfitem).getItem();
        assertTrue("the pending path must be cleared so the ingest cannot repeat",
                itemService.getMetadataByMetadataString(item, MD_FIELD).isEmpty());
    }

    /** The submitter may write to their own submission, but may not read arbitrary server files through it. */
    @Test
    public void nonAdminSubmitterCannotIngest() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String submitterToken = getAuthToken(eperson.getEmail(), password);
        getClient(submitterToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Attempted by the submitter"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isForbidden());

        assertTrue("the refusal must happen before the source file is touched", Files.exists(sourceFile));
        getClient(submitterToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)));
    }

    /**
     * The privilege-escalation regression: a reviewer legitimately holds a claimed task, so the only
     * gate on the workflow PATCH endpoint is the driver's site-administrator check.
     */
    @Test
    public void workflowReviewerWhoIsNotAdminCannotIngest() throws Exception {
        context.turnOffAuthorisationSystem();
        EPerson reviewer = EPersonBuilder.createEPerson(context)
                .withEmail("reviewer@example.com")
                .withPassword(password)
                .build();
        XmlWorkflowItem wfitem = claimedWorkflowItem(reviewer);
        setPendingPath(wfitem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String reviewerToken = getAuthToken(reviewer.getEmail(), password);
        getClient(reviewerToken).perform(patch("/api/workflow/workflowitems/" + wfitem.getID())
                        .content(replaceTitlePatch("Attempted by the reviewer"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isForbidden());

        assertTrue("the refusal must happen before the source file is touched", Files.exists(sourceFile));
        getClient(reviewerToken).perform(get("/api/workflow/workflowitems/" + wfitem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)));
    }

    /** The field takes a server path, never a URL - there is no HTTP download behind it. */
    @Test
    public void rejectsUrlValue() throws Exception {
        assertIngestRefused("https://example.org/big.zip");
    }

    /** A readable file outside every configured root must be refused. */
    @Test
    public void rejectsPathOutsideAllowList() throws Exception {
        Path outsideFile = Files.write(outsideRoot.resolve("secret.bin"), FILE_CONTENT);
        assertIngestRefused(outsideFile.toString());
        assertTrue("a rejected file must be left alone", Files.exists(outsideFile));
    }

    /** With the master switch absent the feature is inert, even for a site administrator. */
    @Test
    public void featureDisabledByDefault() throws Exception {
        configurationService.setProperty(CFG_ENABLED, null);
        assertIngestRefused(sourceFile.toString());
        assertTrue("a rejected file must be left alone", Files.exists(sourceFile));
    }

    /** An ordinary save on an item without the field must behave exactly as before. */
    @Test
    public void noOpWhenFieldAbsent() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        context.restoreAuthSystemState();

        String submitterToken = getAuthToken(eperson.getEmail(), password);
        getClient(submitterToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("An ordinary edit"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value", is("An ordinary edit")))
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)));
    }

    /**
     * A blank value is tidied away without an authorisation check, so an ordinary submitter saving a form
     * that carries the field must never be refused.
     */
    @Test
    public void blankValueIsNoOpForNonAdmin() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), "   ");
        context.restoreAuthSystemState();

        String submitterToken = getAuthToken(eperson.getEmail(), password);
        getClient(submitterToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("An edit by the submitter"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)));

        Item item = context.reloadEntity(witem).getItem();
        assertTrue("a blank pending path must be tidied away rather than left to be retried",
                itemService.getMetadataByMetadataString(item, MD_FIELD).isEmpty());
    }

    /**
     * The bitstream must be the file that was named, byte for byte. A name and a length alone would let
     * a truncated or misdirected read through, so the recorded checksum is compared with one computed
     * here and the stored content is downloaded and compared as well.
     */
    @Test
    public void ingestedBitstreamHoldsTheSourceBytes() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        MvcResult result = getClient(adminToken)
                .perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Ingested with its content checked"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)))
                .andExpect(jsonPath("$.sections.upload.files[0].sizeBytes", is(FILE_CONTENT.length)))
                .andExpect(jsonPath("$.sections.upload.files[0].checkSum.checkSumAlgorithm", is("MD5")))
                .andExpect(jsonPath("$.sections.upload.files[0].checkSum.value", is(Utils.getMD5(FILE_CONTENT))))
                .andReturn();

        String bitstreamId = read(result.getResponse().getContentAsString(), "$.sections.upload.files[0].uuid");
        getClient(adminToken).perform(get("/api/core/bitstreams/" + bitstreamId + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(FILE_CONTENT));
    }

    /**
     * The upload step reports a failed store by returning an error rather than by throwing, and the
     * pending path has already been cleared by the time it does. The save must therefore roll back as a
     * whole - not just the bitstream, the edit that accompanied it too - so that the path the
     * administrator typed survives for a retry.
     * <p>
     * The failure is provoked the way a full or unwritable assetstore volume would provoke it, by
     * pointing the store at a location it cannot create.
     */
    @Test
    public void failedUploadStepRollsBackTheWholeSaveAndKeepsThePointer() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        breakAssetstore();
        try {
            getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                            .content(replaceTitlePatch("Saved onto a broken assetstore"))
                            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isUnprocessableEntity());
        } finally {
            assetstore.setBaseDir(originalAssetstoreBaseDir);
        }

        getClient(adminToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)))
                .andExpect(jsonPath(PENDING_PATH_JSON + "[0].value", is(sourceFile.toString())))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Submission under test")));
        assertTrue("a failed ingest must not consume the source file", Files.exists(sourceFile));
    }

    /**
     * A submission process with no upload step reports no error and creates nothing, so a driver that
     * only looked at the error list would clear the pointer and answer 200 with the file silently
     * discarded. The save must be refused and rolled back instead.
     */
    @Test
    public void refusesToIngestWhenTheSubmissionHasNoUploadStep() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        // This handle is mapped to the "typebindtest" submission process, which has no upload step.
        Collection collection = CollectionBuilder
                .createCollection(context, parentCommunity, "123456789/typebind-test")
                .withName("Collection without an upload step")
                .build();
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("Submission under test")
                .withIssueDate("2024-01-17")
                .build();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        List<Map<String, String>> values = new ArrayList<>();
        Map<String, String> value = new HashMap<>();
        value.put("value", "Book");
        values.add(value);
        List<Operation> operations = new ArrayList<>();
        operations.add(new AddOperation("/sections/typebindtest/dc.type", values));

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(getPatchContent(operations))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isBadRequest());

        assertTrue("a refused ingest must leave the source file alone", Files.exists(sourceFile));
        Item item = context.reloadEntity(witem).getItem();
        assertEquals("the pointer must survive, so the administrator can retry somewhere it can be ingested",
                1, itemService.getMetadataByMetadataString(item, MD_FIELD).size());
        assertEquals("and it must still name the same file", sourceFile.toString(),
                itemService.getMetadataByMetadataString(item, MD_FIELD).get(0).getValue());
    }

    /**
     * The submission form hides the field from non-administrators, but hiding is not enforcement: the
     * PATCH endpoint has to refuse the write itself. A blank value is refused along with a real path,
     * because it is the guard rather than the ingest that has to be doing the refusing - the ingest
     * treats a blank value as a no-op.
     */
    @Test
    public void ordinaryUserCannotWriteThePendingPathThroughTheApi() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        context.restoreAuthSystemState();

        String submitterToken = getAuthToken(eperson.getEmail(), password);
        getClient(submitterToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(pendingPathPatch(sourceFile.toString()))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isForbidden());
        getClient(submitterToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(pendingPathPatch("   "))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isForbidden());

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PENDING_PATH_JSON).doesNotExist())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)));
        assertTrue("nothing may be ingested by a refused write", Files.exists(sourceFile));
    }

    /**
     * The same refusal on the workflow endpoint, which has no {@code @PreAuthorize} of its own: a
     * reviewer holds full workflow policies on the item, so the guard is all there is.
     */
    @Test
    public void workflowReviewerCannotWriteThePendingPathThroughTheApi() throws Exception {
        context.turnOffAuthorisationSystem();
        EPerson reviewer = EPersonBuilder.createEPerson(context)
                .withEmail("writing-reviewer@example.com")
                .withPassword(password)
                .build();
        XmlWorkflowItem wfitem = claimedWorkflowItem(reviewer);
        context.restoreAuthSystemState();

        String reviewerToken = getAuthToken(reviewer.getEmail(), password);
        getClient(reviewerToken).perform(patch("/api/workflow/workflowitems/" + wfitem.getID())
                        .content(pendingPathPatch(sourceFile.toString()))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isForbidden());
        getClient(reviewerToken).perform(patch("/api/workflow/workflowitems/" + wfitem.getID())
                        .content(pendingPathPatch("   "))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isForbidden());

        Item item = context.reloadEntity(wfitem).getItem();
        assertTrue("a refused write must leave nothing behind on the item",
                itemService.getMetadataByMetadataString(item, MD_FIELD).isEmpty());
    }

    /**
     * The counterpart of the refusal above, and the reason it only covers writes: a removal must always
     * be allowed. A value that reached the item some other way is invisible to its submitter, so if
     * clearing it were refused too they could never save their own submission again.
     */
    @Test
    public void ordinaryUserMayStillClearAPendingPathTheyCannotSee() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        List<Operation> operations = new ArrayList<>();
        operations.add(new RemoveOperation(PENDING_PATH_OP + "/0"));

        String submitterToken = getAuthToken(eperson.getEmail(), password);
        getClient(submitterToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(getPatchContent(operations))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PENDING_PATH_JSON).doesNotExist())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)));

        assertTrue("clearing the field must not ingest anything", Files.exists(sourceFile));
        Item item = context.reloadEntity(witem).getItem();
        assertTrue("the value the submitter removed must be gone",
                itemService.getMetadataByMetadataString(item, MD_FIELD).isEmpty());
    }

    /**
     * The field takes one path. Two values mean something wrote to it out of band, and only the first
     * would ever be ingested while clearing destroyed the rest, so the save is refused with both values
     * and both files left as they were.
     */
    @Test
    public void refusesMoreThanOnePendingPathWithoutDestroyingEither() throws Exception {
        context.turnOffAuthorisationSystem();
        Path secondFile = Files.write(allowedRoot.resolve("ingest-me-too.bin"), FILE_CONTENT);
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        setPendingPath(witem.getItem(), secondFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Two paths at once"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isBadRequest());

        getClient(adminToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)))
                .andExpect(jsonPath(PENDING_PATH_JSON, hasSize(2)));
        assertTrue("neither file may be touched by a refused save", Files.exists(sourceFile));
        assertTrue("neither file may be touched by a refused save", Files.exists(secondFile));
    }

    /**
     * The configured cap is enforced, and it is a boundary rather than a blanket refusal: a file one
     * byte too large is refused with the pointer intact, and the very same file is ingested once the
     * cap admits it.
     */
    @Test
    public void refusesAFileAboveTheConfiguredMaximum() throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        configurationService.setProperty(CFG_MAX_BYTES, FILE_CONTENT.length - 1);

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Over the cap"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isBadRequest());

        getClient(adminToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)))
                .andExpect(jsonPath(PENDING_PATH_JSON + "[0].value", is(sourceFile.toString())));
        assertTrue("a file refused by the cap must be left alone", Files.exists(sourceFile));

        configurationService.setProperty(CFG_MAX_BYTES, FILE_CONTENT.length);
        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Exactly at the cap"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)))
                .andExpect(jsonPath("$.sections.upload.files[0].sizeBytes", is(FILE_CONTENT.length)));
    }

    /**
     * With delete-after-upload on, the source file is removed once its bitstream is durable. That
     * removal commits the transaction before it unlinks anything, so the response has to be checked as
     * well: it must still serialise the submission it was going to serialise anyway.
     */
    @Test
    public void deletesTheSourceFileWhenDeleteAfterUploadIsOn() throws Exception {
        configurationService.setProperty(CFG_DELETE_AFTER, true);

        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Ingested from a staging area"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)))
                .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value", is(FILE_NAME)))
                .andExpect(jsonPath("$.sections.upload.files[0].sizeBytes", is(FILE_CONTENT.length)))
                .andExpect(jsonPath("$.sections.upload.files[0].checkSum.value", is(Utils.getMD5(FILE_CONTENT))))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Ingested from a staging area")))
                .andExpect(jsonPath(PENDING_PATH_JSON).doesNotExist());

        assertFalse("the source file must be gone once its bitstream is committed", Files.exists(sourceFile));
        assertTrue("only the file may be removed, never the staging directory", Files.exists(allowedRoot));
    }

    /**
     * The same removal on a workflow item, where committing from inside the PATCH costs more: the workflow
     * item and the claimed task that authorises the edit are in that transaction too, and the response is
     * serialised from a fresh read taken after the commit. So the body is checked field by field rather
     * than by its status alone, the item is read back, and a further save proves the task survived.
     */
    @Test
    public void deletesTheSourceFileWhenDeleteAfterUploadIsOnForAWorkflowItem() throws Exception {
        configurationService.setProperty(CFG_DELETE_AFTER, true);

        context.turnOffAuthorisationSystem();
        XmlWorkflowItem wfitem = claimedWorkflowItem(admin);
        setPendingPath(wfitem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/workflow/workflowitems/" + wfitem.getID())
                        .content(replaceTitlePatch("Ingested from a staging area during review"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(wfitem.getID())))
                .andExpect(jsonPath("$.type", is("workflowitem")))
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)))
                .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value", is(FILE_NAME)))
                .andExpect(jsonPath("$.sections.upload.files[0].sizeBytes", is(FILE_CONTENT.length)))
                .andExpect(jsonPath("$.sections.upload.files[0].checkSum.value", is(Utils.getMD5(FILE_CONTENT))))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Ingested from a staging area during review")))
                .andExpect(jsonPath(PENDING_PATH_JSON).doesNotExist());

        assertFalse("the source file must be gone once its bitstream is committed", Files.exists(sourceFile));
        Item item = context.reloadEntity(wfitem).getItem();
        assertTrue("the pending path must be cleared so the ingest cannot repeat",
                itemService.getMetadataByMetadataString(item, MD_FIELD).isEmpty());

        getClient(adminToken).perform(get("/api/workflow/workflowitems/" + wfitem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(wfitem.getID())))
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)))
                .andExpect(jsonPath("$.sections.upload.files[0].sizeBytes", is(FILE_CONTENT.length)))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Ingested from a staging area during review")))
                .andExpect(jsonPath(PENDING_PATH_JSON).doesNotExist());
        // Reading the item back does not exercise the claimed task, and losing it to the commit would be
        // invisible until the next save: without it the endpoint answers 422 rather than 200.
        getClient(adminToken).perform(patch("/api/workflow/workflowitems/" + wfitem.getID())
                        .content(replaceTitlePatch("Edited again after the ingest"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)));
    }

    /**
     * The destructive branch is opt-in: with the setting at its default the source file stays where it
     * is, even though it has been ingested successfully.
     */
    @Test
    public void keepsTheSourceFileWhenDeleteAfterUploadIsOff() throws Exception {
        configurationService.setProperty(CFG_DELETE_AFTER, false);

        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), sourceFile.toString());
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Ingested from a shared directory"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(1)));

        assertTrue("the source file must survive an ingest that was not asked to remove it",
                Files.exists(sourceFile));
        assertEquals("and it must survive unchanged", FILE_CONTENT.length, Files.size(sourceFile));
    }

    /**
     * PATCH a workspace item carrying the given pending value as a site administrator and assert the
     * request is refused with 400 and nothing is ingested.
     */
    private void assertIngestRefused(String pendingValue) throws Exception {
        context.turnOffAuthorisationSystem();
        WorkspaceItem witem = workspaceItem();
        setPendingPath(witem.getItem(), pendingValue);
        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(replaceTitlePatch("Refused"))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isBadRequest());

        getClient(adminToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)));
    }

    private WorkspaceItem workspaceItem() throws Exception {
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1")
                .withSubmitterGroup(eperson)
                .build();
        return WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("Submission under test")
                .withIssueDate("2024-01-17")
                .grantLicense()
                .build();
    }

    /** Build a workflow item sitting in the edit-metadata step with its task claimed by the given reviewer. */
    private XmlWorkflowItem claimedWorkflowItem(EPerson reviewer) throws Exception {
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1")
                .withWorkflowGroup(2, reviewer)
                .build();
        ClaimedTask claimedTask = ClaimedTaskBuilder.createClaimedTask(context, collection, reviewer)
                .withTitle("Review under test")
                .withIssueDate("2024-01-17")
                .grantLicense()
                .build();
        claimedTask.setStepID("editstep");
        claimedTask.setActionID("editaction");
        return claimedTask.getWorkflowItem();
    }

    /**
     * Build a workflow item sitting in the final edit step with its task claimed by the given reviewer.
     * <p>
     * A step whose role group has no members is skipped, so populating the third workflow group and no
     * other carries the item past reviewstep and editstep and lands it in finaleditstep. That is an
     * arrangement rather than an assertion, so the step and action actually reached are checked here:
     * without that the test could quietly be a second copy of the edit-step one.
     */
    private XmlWorkflowItem claimedFinalEditWorkflowItem(EPerson reviewer) throws Exception {
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1")
                .withWorkflowGroup(3, reviewer)
                .build();
        ClaimedTask claimedTask = ClaimedTaskBuilder.createClaimedTask(context, collection, reviewer)
                .withTitle("Final edit under test")
                .withIssueDate("2024-01-17")
                .grantLicense()
                .build();
        assertNotNull("the final editor must hold a claimed task, or the PATCH is refused as unclaimed",
                claimedTask);
        assertEquals("the item must reach the last step of the workflow", "finaleditstep",
                claimedTask.getStepID());
        assertEquals("and FinalEditAction must be the action being exercised", "finaleditaction",
                claimedTask.getActionID());
        return claimedTask.getWorkflowItem();
    }

    /** Record a pending server path on the item, as saving the submission form would. */
    private void setPendingPath(Item item, String value) throws Exception {
        itemService.addMetadata(context, item, "local", "bitstream", "redirectToURL", null, value);
        itemService.update(context, item);
    }

    private String replaceTitlePatch(String title) {
        Map<String, String> value = new HashMap<>();
        value.put("value", title);
        List<Operation> operations = new ArrayList<>();
        operations.add(new ReplaceOperation("/sections/traditionalpageone/dc.title/0", value));
        return getPatchContent(operations);
    }

    /** A PATCH body that writes the given value into the pending-path field, as the form would. */
    private String pendingPathPatch(String pendingValue) {
        Map<String, String> value = new HashMap<>();
        value.put("value", pendingValue);
        List<Map<String, String>> values = new ArrayList<>();
        values.add(value);
        List<Operation> operations = new ArrayList<>();
        operations.add(new AddOperation(PENDING_PATH_OP, values));
        return getPatchContent(operations);
    }

    /**
     * Point the assetstore at a directory that cannot be created, because its parent is a regular file.
     * Writing a bitstream then fails with an I/O error, which is how a full or unwritable volume
     * presents itself, and which the upload step reports by returning an error rather than throwing.
     */
    private void breakAssetstore() throws Exception {
        assetstore = DSpaceServicesFactory.getInstance().getServiceManager()
                .getServiceByName("localStore", DSBitStoreService.class);
        assertNotNull("the local assetstore bean must be resolvable to simulate a failed store", assetstore);
        originalAssetstoreBaseDir = assetstore.getBaseDir();
        Path blocker = Files.write(outsideRoot.resolve("not-a-directory"), new byte[] {0});
        assetstore.setBaseDir(blocker.resolve("assetstore").toFile());
    }
}

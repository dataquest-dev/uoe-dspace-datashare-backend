/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.exception.RESTAuthorizationException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.AInprogressSubmissionRest;
import org.dspace.app.rest.model.ErrorRest;
import org.dspace.app.rest.model.patch.AddOperation;
import org.dspace.app.rest.model.patch.JsonValueEvaluator;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.utils.BigMultipartFile;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bundle;
import org.dspace.content.InProgressSubmission;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Ingests a file already on the server filesystem as a bitstream of an in-progress submission, driven by
 * the {@value #MD_FIELD} metadata field. Streamed straight into the assetstore (no browser size limit);
 * disabled by default, restricted to site administrators, and confined to an allow-list of roots.
 * <p>
 * {@link #ingestPendingFile} must run inside the repository PATCH transaction and does not commit, so any
 * failure rolls the whole save back. {@link #deleteIngestedSource} is the exception and says why.
 */
@Component
public class UploadFromPathService {

    /** Metadata schema of the field carrying the pending server path. */
    public static final String MD_SCHEMA = "local";
    /** Metadata element of the field carrying the pending server path. */
    public static final String MD_ELEMENT = "bitstream";
    /** Metadata qualifier of the field carrying the pending server path. */
    public static final String MD_QUALIFIER = "redirectToURL";
    /** Fully qualified name of the field carrying the pending server path. */
    public static final String MD_FIELD = "local.bitstream.redirectToURL";

    static final String CFG_ENABLED = "bitstream.upload-from-path.enabled";
    static final String CFG_ALLOWED_PATHS = "bitstream.upload-from-path.allowed-paths";
    static final String CFG_DELETE_AFTER = "bitstream.upload-from-path.delete-after-upload";
    static final String CFG_MAX_BYTES = "bitstream.upload-from-path.max-bytes";

    private static final Logger log = LogManager.getLogger(UploadFromPathService.class);

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Refuse a write to {@value #MD_FIELD} by anyone who is not a site administrator; removals are always
     * allowed. The form &lt;acl&gt; only hides the field, it is not write-path enforcement. Guards the two
     * submission PATCH endpoints only, so the check inside {@link #ingestPendingFile} is kept as well for
     * metadata written by other routes.
     *
     * @param context    the current DSpace Context
     * @param operations the operations of the PATCH that is about to be applied
     * @throws SQLException if the administrator check cannot be resolved
     */
    public void assertMayWritePendingPath(Context context, List<Operation> operations) throws SQLException {
        for (Operation operation : operations) {
            if (writesPendingPath(operation) && !authorizeService.isAdmin(context)) {
                throw new RESTAuthorizationException(
                        "Only site administrators may set " + MD_FIELD + ".");
            }
        }
    }

    /**
     * Rewrite a {@code replace} on {@value #MD_FIELD} as an {@code add} when the item holds no value for
     * that field. The field is cleared server-side after each ingest, so a stale client sends a
     * {@code replace} that {@code DescribeStep} would 500 on; rewriting it avoids the crash and ingests
     * the new path. A {@code replace} against a field that genuinely holds a value is left untouched.
     *
     * @param source     the workspace or workflow item being edited
     * @param operations the operations of the PATCH about to be applied
     * @return the operations to apply, with a rewritten entry where necessary
     */
    public List<Operation> normalizePendingPathOperations(InProgressSubmission source, List<Operation> operations) {
        if (!itemService.getMetadataByMetadataString(source.getItem(), MD_FIELD).isEmpty()) {
            // Field still holds a value, so a replace against it is legitimate.
            return operations;
        }
        List<Operation> normalized = new ArrayList<>(operations.size());
        for (Operation operation : operations) {
            Operation rewritten = rewritePendingPathReplaceAsAdd(operation);
            normalized.add(rewritten != null ? rewritten : operation);
        }
        return normalized;
    }

    /**
     * Rewrites a {@code replace} on {@value #MD_FIELD}{@code /<index>} into an {@code add} whose value is
     * wrapped in a single-element array, the shape {@code DescribeStep} expects. Returns null for anything
     * that is not such a replace.
     */
    private Operation rewritePendingPathReplaceAsAdd(Operation operation) {
        if (!"replace".equals(operation.getOp()) || !(operation.getValue() instanceof JsonValueEvaluator)) {
            return null;
        }
        String path = StringUtils.defaultString(operation.getPath());
        String marker = "/" + MD_FIELD + "/";
        int fieldAt = path.indexOf(marker);
        if (fieldAt < 0 || !StringUtils.isNumeric(path.substring(fieldAt + marker.length()))) {
            return null;
        }
        JsonNode single = ((JsonValueEvaluator) operation.getValue()).getValueNode();
        ArrayNode asArray = objectMapper.createArrayNode().add(single);
        String addPath = path.substring(0, fieldAt) + "/" + MD_FIELD;
        return new AddOperation(addPath, new JsonValueEvaluator(objectMapper, asArray));
    }

    /**
     * If the submission carries a pending {@value #MD_FIELD} value, ingest that server-side file as a
     * bitstream and clear the field; no-op when absent. Nothing is deleted here, and a failed ingest
     * throws so the enclosing PATCH rolls back and the typed path survives for a retry.
     *
     * @param context the current DSpace Context
     * @param request the current PATCH request, forwarded to the submission steps
     * @param rest    the REST view of the submission, which supplies the submission definition name
     * @param source  the workspace or workflow item being edited
     * @return the source file to hand to {@link #deleteIngestedSource} once the PATCH is durable, or null
     * @throws SQLException       if a database error occurs
     * @throws AuthorizeException if the caller may not modify the item
     * @throws IOException        if the source file cannot be read
     */
    public Path ingestPendingFile(Context context, HttpServletRequest request,
                                  AInprogressSubmissionRest rest, InProgressSubmission source)
            throws SQLException, AuthorizeException, IOException {

        Item item = source.getItem();
        List<MetadataValue> pending = itemService.getMetadataByMetadataString(item, MD_FIELD);
        if (pending.isEmpty()) {
            return null;
        }
        if (pending.size() == 1 && StringUtils.isBlank(pending.get(0).getValue())) {
            // A blank value asks for nothing; tidy it away without demanding administrator rights.
            clearPendingPath(context, item);
            return null;
        }

        // Checked before the master switch so the refusal is identical either way and cannot probe config.
        // The workflow PATCH endpoint has no @PreAuthorize, so this is the only gate on the feature.
        if (!authorizeService.isAdmin(context)) {
            throw new RESTAuthorizationException(
                    "Only site administrators may ingest a bitstream from a server path.");
        }

        if (!configurationService.getBooleanProperty(CFG_ENABLED, false)) {
            throw new DSpaceBadRequestException("Upload-from-path is not enabled on this server.");
        }

        // Non-repeatable field: several values mean an out-of-band write, so refuse rather than lose data.
        if (pending.size() > 1) {
            throw new DSpaceBadRequestException(pending.size() + " values were found in " + MD_FIELD
                    + ", which takes a single path. Remove all but the one to ingest, then save again.");
        }

        String raw = pending.get(0).getValue().trim();
        long max = configurationService.getLongProperty(CFG_MAX_BYTES, -1L);
        Path file;
        long size;
        List<ErrorRest> errors;
        int bitstreamsBefore;
        try (UploadFromPathValidator.OpenSourceFile opened = openSource(raw)) {
            file = opened.getPath();
            size = opened.getSize();
            if (max > 0 && size > max) {
                throw new DSpaceBadRequestException(
                        "The file is " + size + " bytes, above the configured maximum of " + max + " bytes.");
            }

            // Cleared before the ingest so a partial failure leaves a retryable missing bitstream, not a
            // pointer that re-ingests on every save.
            clearPendingPath(context, item);

            bitstreamsBefore = countOriginalBitstreams(item);
            String contentType = URLConnection.guessContentTypeFromName(file.getFileName().toString());
            BigMultipartFile multipartFile = new BigMultipartFile(file.getFileName().toString(),
                    file.getFileName().toString(), contentType, size, opened.getInputStream());
            // NOTE: SubmissionService replays this single MultipartFile against every UploadableStep;
            //       safe only while UploadStep is the sole active one (a second would get an exhausted stream).
            errors = submissionService.uploadFileToInprogressSubmission(context, request, rest, source,
                    multipartFile);
        }

        // UploadStep reports a failed store by returning an error, not throwing; returning it would let
        // the PATCH commit with no bitstream and the path cleared. Throwing rolls the save back for retry.
        if (!errors.isEmpty()) {
            throw new UnprocessableEntityException(
                    "Ingest of '" + file + "' failed: " + firstMessage(errors));
        }

        // A submission process with no upload step reports no error and creates nothing; fail so the
        // transaction rolls back rather than silently discarding the file.
        if (countOriginalBitstreams(item) == bitstreamsBefore) {
            throw new DSpaceBadRequestException(
                    "This collection's submission process has no upload step; the file was not ingested.");
        }

        itemService.addMetadata(context, item, "dc", "description", "provenance", "en",
                String.format("Bitstream '%s' (%d bytes) ingested from server path '%s' by %s on %s",
                        file.getFileName(), size, file, describe(context.getCurrentUser()), Instant.now()));
        itemService.update(context, item);
        log.info("Ingested {} ({} bytes) from server path {} into item {}",
                file.getFileName(), size, file, item.getID());

        return configurationService.getBooleanProperty(CFG_DELETE_AFTER, false) ? file : null;
    }

    /**
     * Remove a source file that {@link #ingestPendingFile} flagged, after committing the PATCH that
     * created its bitstream. The commit must come first: the unlink is irreversible and the bitstream is
     * not, so a failure this way only ever leaves the file behind, never destroys it for a save that did
     * not happen. Committed here (the last statement of the PATCH) because Context has no post-commit hook.
     *
     * @param context the current DSpace Context, committed before anything is removed
     * @param file    the file to remove, or null when there is nothing to do
     * @throws SQLException if the transaction cannot be committed, in which case nothing is removed
     */
    public void deleteIngestedSource(Context context, Path file) throws SQLException {
        if (file == null) {
            return;
        }
        context.commit();
        // Safe to delete: validateAndOpen() proved this canonical path is inside an allow-list root.
        try {
            Files.delete(file);
            log.info("Deleted source file {} after ingest, because {} is enabled", file, CFG_DELETE_AFTER);
        } catch (IOException e) {
            log.warn("Could not delete source file {} after ingest", file, e);
        }
    }

    /**
     * Built per call so an allow-list change takes effect without a restart, against the current config.
     */
    private UploadFromPathValidator validator() {
        return new UploadFromPathValidator(configurationService.getArrayProperty(CFG_ALLOWED_PATHS));
    }

    private UploadFromPathValidator.OpenSourceFile openSource(String raw) {
        try {
            return validator().validateAndOpen(raw);
        } catch (UploadFromPathException e) {
            throw new DSpaceBadRequestException(e.getMessage(), e);
        }
    }

    /**
     * Any operation other than a removal that names the field is a write to it; matched on a path segment.
     */
    private boolean writesPendingPath(Operation operation) {
        if ("remove".equals(operation.getOp())) {
            return false;
        }
        for (String segment : StringUtils.split(StringUtils.defaultString(operation.getPath()), '/')) {
            if (MD_FIELD.equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private String firstMessage(List<ErrorRest> errors) {
        String message = errors.get(0).getMessage();
        return StringUtils.isBlank(message) ? "the upload step reported no reason" : message;
    }

    private void clearPendingPath(Context context, Item item) throws SQLException, AuthorizeException {
        itemService.clearMetadata(context, item, MD_SCHEMA, MD_ELEMENT, MD_QUALIFIER, Item.ANY);
        itemService.update(context, item);
    }

    private int countOriginalBitstreams(Item item) throws SQLException {
        int count = 0;
        for (Bundle bundle : itemService.getBundles(item, Constants.CONTENT_BUNDLE_NAME)) {
            count += bundle.getBitstreams().size();
        }
        return count;
    }

    private String describe(EPerson actor) {
        return actor == null ? "an unknown user" : actor.getEmail() + " (" + actor.getID() + ")";
    }
}

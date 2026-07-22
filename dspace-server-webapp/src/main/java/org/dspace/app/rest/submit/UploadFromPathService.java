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
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.exception.RESTAuthorizationException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.AInprogressSubmissionRest;
import org.dspace.app.rest.model.ErrorRest;
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
 * Ingests a file that already exists on the DSpace server filesystem as a bitstream of an in-progress
 * submission, driven by the {@value #MD_FIELD} metadata field.
 * <p>
 * The file is streamed straight into the assetstore through the normal submission upload steps, so its
 * size is not bounded by the browser upload path. Because that is a server-side file read performed with
 * the servlet container's privileges, it is disabled by default, restricted to site administrators, and
 * limited to files inside a configured allow-list of directory roots.
 * <p>
 * {@link #ingestPendingFile} must be called from inside the repository PATCH transaction: it
 * deliberately does not commit, so a failure anywhere rolls the whole save back and a retry starts from
 * a clean state. {@link #deleteIngestedSource} is the exception and says why.
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

    /**
     * Refuse an attempt to write {@value #MD_FIELD} by anyone who is not a site administrator.
     * <p>
     * The &lt;acl&gt; on the submission form only hides the field from the payload; it is not
     * write-path enforcement, so without this check a collection administrator or a workflow reviewer
     * could put a path into the field and have the next save by a site administrator ingest it under
     * that administrator's identity. Removals are always allowed, so nobody can be locked out of their
     * own submission by a value they cannot see.
     * <p>
     * This guards the two submission PATCH endpoints, which are the only routes into
     * {@link #ingestPendingFile}. Metadata written by another route entirely - {@code /api/core/items},
     * a collection's template item, a CLI import - still reaches the item, which is why the
     * administrator check inside {@link #ingestPendingFile} is kept as well.
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
     * If the in-progress submission carries a pending {@value #MD_FIELD} value, ingest that server-side
     * file as a bitstream and clear the field. No-op when the field is absent.
     * <p>
     * Nothing is deleted here. A failed ingest throws, so that the enclosing PATCH rolls back and the
     * path the administrator typed survives for a retry.
     *
     * @param context the current DSpace Context
     * @param request the current PATCH request, forwarded to the submission steps
     * @param rest    the REST view of the submission, which supplies the submission definition name
     * @param source  the workspace or workflow item being edited
     * @return the source file the caller must hand to {@link #deleteIngestedSource} once the PATCH is
     *         durable, or null when there is nothing to delete
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
            // A blank value asks for nothing, so tidy it away without demanding administrator rights:
            // a user who cannot even see the field must not be blocked by it.
            clearPendingPath(context, item);
            return null;
        }

        // Checked before the master switch so that the refusal is identical whether or not the feature
        // is switched on, and so a non-administrator cannot use it to probe the configuration.
        // The workflow PATCH endpoint has no @PreAuthorize, so this is the only gate on the feature.
        if (!authorizeService.isAdmin(context)) {
            throw new RESTAuthorizationException(
                    "Only site administrators may ingest a bitstream from a server path.");
        }

        if (!configurationService.getBooleanProperty(CFG_ENABLED, false)) {
            throw new DSpaceBadRequestException("Upload-from-path is not enabled on this server.");
        }

        // The field is non-repeatable, so several values mean something wrote to it out of band. Only
        // the first would ever be ingested and clearing would then destroy the rest, so refuse instead.
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
        try (UploadFromPathPathValidator.OpenSourceFile opened = openSource(raw)) {
            file = opened.getPath();
            size = opened.getSize();
            if (max > 0 && size > max) {
                throw new DSpaceBadRequestException(
                        "The file is " + size + " bytes, above the configured maximum of " + max + " bytes.");
            }

            // Cleared before the ingest: the worst outcome of a partial failure is then a missing
            // bitstream, which the administrator can retry, rather than a pointer that re-ingests on
            // every save.
            clearPendingPath(context, item);

            bitstreamsBefore = countOriginalBitstreams(item);
            String contentType = URLConnection.guessContentTypeFromName(file.getFileName().toString());
            BigMultipartFile multipartFile = new BigMultipartFile(file.getFileName().toString(),
                    file.getFileName().toString(), contentType, size, opened.getInputStream());
            // NOTE: SubmissionService replays this single MultipartFile against every UploadableStep in
            //       the configuration. That is safe only while UploadStep is the sole active one
            //       (extractionstep is disabled); a second step would receive an exhausted stream and
            //       would need its own stream opened here.
            errors = submissionService.uploadFileToInprogressSubmission(context, request, rest, source,
                    multipartFile);
        }

        // UploadStep reports a failed store by RETURNING an error rather than throwing, so returning it
        // to the caller would let the PATCH commit: no bitstream, and the path already cleared above.
        // Throwing rolls the whole save back, which is the only outcome the administrator can retry.
        if (!errors.isEmpty()) {
            throw new UnprocessableEntityException(
                    "Ingest of '" + file + "' failed: " + firstMessage(errors));
        }

        // A submission process with no upload step reports no error and creates nothing. Failing here
        // rolls the transaction back, rather than silently discarding the file the pointer named.
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
     * Remove a source file that {@link #ingestPendingFile} asked to have removed, once the PATCH that
     * created its bitstream has been committed.
     * <p>
     * The unlink is irreversible and the bitstream it is traded for is not, so the two must not be
     * ordered the other way round: a commit that failed afterwards would leave the file gone with
     * nothing to show for it. {@link Context} in this codebase offers no post-commit callback - the
     * only deferred mechanism, {@code addEvent}, is dispatched from inside {@code commit()} while the
     * transaction is still open - so the transaction is committed here instead. This is the last
     * statement of the PATCH, after every database write the ingest needs, so it is not a nested commit
     * mid-request; the outer {@code commit()} that follows finds nothing left to do.
     * <p>
     * Residual risk, in the safe direction only: if the process dies between the commit and the unlink,
     * the source file is left behind. Nothing is ever destroyed for a save that did not happen.
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
     * Built per call so that a change to the allow-list takes effect without restarting the server, and
     * so that the fail-closed behaviour of an empty allow-list is evaluated against the current config.
     */
    private UploadFromPathPathValidator validator() {
        return new UploadFromPathPathValidator(configurationService.getArrayProperty(CFG_ALLOWED_PATHS));
    }

    private UploadFromPathPathValidator.OpenSourceFile openSource(String raw) {
        try {
            return validator().validateAndOpen(raw);
        } catch (UploadFromPathException e) {
            throw new DSpaceBadRequestException(e.getMessage(), e);
        }
    }

    /**
     * Any operation other than a removal that names the field is a write to it. Matching on a path
     * segment rather than on one expected shape keeps the guard honest as patch paths gain indices.
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

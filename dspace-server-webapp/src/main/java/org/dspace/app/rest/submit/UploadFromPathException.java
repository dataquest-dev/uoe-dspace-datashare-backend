/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit;

/**
 * Raised when a value offered for server-side ingest cannot be accepted.
 * <p>
 * The message is written to be shown to the administrator who submitted the value, so it must never
 * disclose the configured allow-list nor confirm the existence of anything outside it. Callers in the
 * REST layer translate this into a {@code DSpaceBadRequestException}; keeping a dedicated type here
 * lets {@link UploadFromPathPathValidator} stay free of Spring and DSpace dependencies.
 */
public class UploadFromPathException extends RuntimeException {

    /**
     * @param message a user-safe explanation of the refusal
     */
    public UploadFromPathException(String message) {
        super(message);
    }
}

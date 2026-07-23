/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit;

/**
 * Raised when a value offered for server-side ingest cannot be accepted. Its message is shown to the
 * administrator, so it must never disclose the allow-list nor confirm anything outside it. A dedicated
 * type keeps {@link UploadFromPathValidator} free of Spring and DSpace dependencies.
 */
public class UploadFromPathException extends RuntimeException {

    /**
     * @param message a user-safe explanation of the refusal
     */
    public UploadFromPathException(String message) {
        super(message);
    }
}

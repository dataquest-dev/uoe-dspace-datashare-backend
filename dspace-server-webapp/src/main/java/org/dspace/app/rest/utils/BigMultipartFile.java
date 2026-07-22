/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.lang.Nullable;
import org.springframework.web.multipart.MultipartFile;

/**
 * A {@link MultipartFile} backed by a file that already exists on the server filesystem.
 * <p>
 * Unlike Spring's own implementations this never buffers the content in memory, which is what makes it
 * usable for multi-gigabyte ingests. The size is supplied by the caller, which is expected to have taken
 * it from the same open handle the stream comes from, so consumers that validate or record a length keep
 * working without anyone having to look the file up by name a second time.
 * <p>
 * The stream belongs to the caller: nothing here closes it, and it can only be read once, so a single
 * instance must not be handed to two consumers.
 */
public class BigMultipartFile implements MultipartFile {

    private final String name;

    private final String originalFilename;

    @Nullable
    private final String contentType;

    private final long size;

    private final InputStream inputStream;

    /**
     * @param name             the name of the parameter this file stands in for
     * @param originalFilename the filename to record on the resulting bitstream
     * @param contentType      the MIME type, or null if it could not be determined
     * @param size             the length of the content in bytes
     * @param inputStream      the content, owned and closed by the caller
     */
    public BigMultipartFile(String name, String originalFilename, @Nullable String contentType,
                            long size, InputStream inputStream) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.size = size;
        this.inputStream = inputStream;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getOriginalFilename() {
        return this.originalFilename;
    }

    @Override
    @Nullable
    public String getContentType() {
        return this.contentType;
    }

    @Override
    public boolean isEmpty() {
        return this.size == 0L;
    }

    @Override
    public long getSize() {
        return this.size;
    }

    @Override
    public InputStream getInputStream() {
        return this.inputStream;
    }

    /**
     * Refused on purpose: materialising a multi-gigabyte file as a {@code byte[]} is never the right
     * answer, and would fail with an unhelpful error above {@link Integer#MAX_VALUE} bytes anyway.
     *
     * @return never returns
     * @throws IOException always
     */
    @Override
    public byte[] getBytes() throws IOException {
        throw new IOException("BigMultipartFile does not support byte[] access; use getInputStream().");
    }

    /**
     * Copy the content to {@code destination} without holding it in memory, so that a consumer which
     * stages uploads to a temporary file still works.
     *
     * @param destination the file to write to
     * @throws IOException if the copy fails
     */
    @Override
    public void transferTo(File destination) throws IOException, IllegalStateException {
        transferTo(destination.toPath());
    }

    /**
     * As {@link #transferTo(File)}. Overridden because the inherited default implementation closes the
     * source stream, which would contradict this class's contract that the stream belongs to the caller.
     *
     * @param destination the file to write to
     * @throws IOException if the copy fails
     */
    @Override
    public void transferTo(Path destination) throws IOException, IllegalStateException {
        try (OutputStream out = Files.newOutputStream(destination)) {
            org.dspace.core.Utils.bufferedCopy(this.inputStream, out);
        }
    }
}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link BigMultipartFile}.
 * <p>
 * Several of these are regressions against the reference implementation this class was derived from,
 * which reported a size of zero and refused to transfer, and against the inherited default
 * {@code transferTo(Path)}, which closes a stream it does not own.
 */
public class BigMultipartFileTest {

    private static final byte[] CONTENT = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void getSizeReturnsRealSize() {
        BigMultipartFile file = newFile(CONTENT);

        assertEquals(CONTENT.length, file.getSize());
    }

    @Test
    public void getNameAndOriginalFilenameAndContentTypePreserved() {
        BigMultipartFile file = new BigMultipartFile("file", "big.bin", "application/octet-stream",
                CONTENT.length, new ByteArrayInputStream(CONTENT));

        assertEquals("file", file.getName());
        assertEquals("big.bin", file.getOriginalFilename());
        assertEquals("application/octet-stream", file.getContentType());
    }

    @Test
    public void contentTypeMayBeNull() {
        BigMultipartFile file = new BigMultipartFile("file", "big.bin", null,
                CONTENT.length, new ByteArrayInputStream(CONTENT));

        assertNull(file.getContentType());
    }

    @Test
    public void isEmptyIsTrueOnlyForZeroLength() {
        assertFalse(newFile(CONTENT).isEmpty());
        assertTrue(newFile(new byte[0]).isEmpty());
    }

    @Test
    public void getInputStreamReturnsFullContent() throws IOException {
        BigMultipartFile file = newFile(CONTENT);

        assertArrayEquals(CONTENT, file.getInputStream().readAllBytes());
    }

    @Test
    public void transferToWritesIdenticalBytes() throws IOException {
        File destination = temporaryFolder.newFile("destination.bin");

        newFile(CONTENT).transferTo(destination);

        assertArrayEquals(CONTENT, Files.readAllBytes(destination.toPath()));
    }

    @Test
    public void transferToLeavesTheStreamOpenForItsOwner() throws IOException {
        CloseRecordingInputStream source = new CloseRecordingInputStream(new ByteArrayInputStream(CONTENT));
        BigMultipartFile file = new BigMultipartFile("file", "big.bin", null, CONTENT.length, source);

        file.transferTo(temporaryFolder.newFile("destination.bin"));

        assertFalse("the caller owns the stream and must be the one to close it", source.closed);
    }

    @Test
    public void transferToPathWritesIdenticalBytesAndLeavesTheStreamOpen() throws IOException {
        CloseRecordingInputStream source = new CloseRecordingInputStream(new ByteArrayInputStream(CONTENT));
        BigMultipartFile file = new BigMultipartFile("file", "big.bin", null, CONTENT.length, source);
        Path destination = temporaryFolder.newFile("destination.bin").toPath();

        file.transferTo(destination);

        assertArrayEquals(CONTENT, Files.readAllBytes(destination));
        assertFalse("the inherited default transferTo(Path) closes the caller's stream; it must be overridden",
                    source.closed);
    }

    @Test
    public void getBytesThrowsIOException() {
        try {
            newFile(CONTENT).getBytes();
            fail("expected getBytes() to refuse a potentially multi-gigabyte array");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("getInputStream"));
        }
    }

    private BigMultipartFile newFile(byte[] content) {
        return new BigMultipartFile("file", "big.bin", "application/octet-stream",
                content.length, new ByteArrayInputStream(content));
    }

    /**
     * Wrapper that records whether anybody closed the underlying stream.
     */
    private static final class CloseRecordingInputStream extends FilterInputStream {

        private boolean closed;

        private CloseRecordingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}

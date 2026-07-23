/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Validates {@code local.bitstream.redirectToURL} and resolves it to a canonical {@link Path} inside a
 * configured allow-list root. Fails closed (no root configured refuses everything) and canonicalises
 * before the containment test to defeat {@code ..} traversal and symlinks. Free of Spring/DSpace deps.
 */
public class UploadFromPathValidator {

    private static final Logger log = LogManager.getLogger(UploadFromPathValidator.class);

    private static final Pattern URL_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*");

    private static final String MSG_URL =
        "URLs are not supported. Provide an absolute path on the server filesystem.";

    private static final String MSG_DISABLED = "Upload-from-path is not enabled on this server.";

    private static final String MSG_NOT_ABSOLUTE = "The path must be absolute.";

    private static final String MSG_INVALID = "The path is not a valid filesystem path.";

    /**
     * Shared by "does not exist" and "outside the allow-list" so that the two cannot be told apart.
     */
    private static final String MSG_UNAVAILABLE =
        "No such file, or the path is outside the directories permitted for server-side ingest.";

    private static final String MSG_NOT_A_FILE = "Not a readable regular file.";

    private final List<Path> allowedRoots;

    /**
     * Canonicalise the configured roots once; a bad entry is logged and dropped rather than fatal.
     *
     * @param configuredRoots the raw values of {@code bitstream.upload-from-path.allowed-paths}, may be null
     */
    public UploadFromPathValidator(String[] configuredRoots) {
        List<Path> roots = new ArrayList<>();
        if (configuredRoots != null) {
            for (String configuredRoot : configuredRoots) {
                Path root = canonicaliseRoot(configuredRoot);
                if (root != null) {
                    roots.add(root);
                }
            }
        }
        this.allowedRoots = Collections.unmodifiableList(roots);
    }

    /**
     * Resolve an administrator-supplied value to a file that may safely be ingested.
     *
     * @param rawValue the metadata value as typed by the administrator
     * @return the canonical path of an existing, readable, regular file inside an allowed root
     * @throws UploadFromPathException with a user-safe message if the value cannot be accepted
     * @throws IllegalStateException   if the value is blank; the caller should have guarded for that
     */
    public Path validate(String rawValue) {
        String raw = rawValue == null ? "" : rawValue.trim();
        if (raw.isEmpty()) {
            throw new IllegalStateException("validate() was called with a blank value");
        }
        if (URL_SCHEME.matcher(raw).matches()) {
            throw new UploadFromPathException(MSG_URL);
        }
        if (allowedRoots.isEmpty()) {
            throw new UploadFromPathException(MSG_DISABLED);
        }

        Path candidate;
        try {
            candidate = Paths.get(raw);
        } catch (InvalidPathException e) {
            throw new UploadFromPathException(MSG_INVALID);
        }
        if (!candidate.isAbsolute()) {
            throw new UploadFromPathException(MSG_NOT_ABSOLUTE);
        }

        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new UploadFromPathException(MSG_UNAVAILABLE);
        }
        if (allowedRoots.stream().noneMatch(real::startsWith)) {
            throw new UploadFromPathException(MSG_UNAVAILABLE);
        }

        // Inside an allowed root by now, so a more precise message here leaks nothing.
        if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
            throw new UploadFromPathException(MSG_NOT_A_FILE);
        }
        return real;
    }

    /**
     * Run {@link #validate} then open the result exactly once, so the ingested file is provably the one
     * validated. Avoids a TOCTOU check/use split: the open uses {@link LinkOption#NOFOLLOW_LINKS} and
     * rechecks identity and canonical path, defeating a symlink swapped in after the containment test.
     *
     * @param rawValue the metadata value as typed by the administrator
     * @return the validated file, held open; the caller must close it
     * @throws UploadFromPathException with a user-safe message if the value cannot be accepted
     * @throws IllegalStateException   if the value is blank; the caller should have guarded for that
     */
    public OpenSourceFile validateAndOpen(String rawValue) {
        Path real = validate(rawValue);
        FileChannel channel = null;
        try {
            Object identityBefore = identityOf(real);
            channel = FileChannel.open(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            if (identityBefore != null && !identityBefore.equals(identityOf(real))) {
                throw new IOException("it was replaced while it was being opened");
            }
            if (!real.equals(real.toRealPath())) {
                throw new IOException("its path no longer resolves to " + real);
            }
            return new OpenSourceFile(real, channel.size(), channel);
        } catch (IOException e) {
            closeQuietly(channel);
            // Reported as "unavailable" like every other refusal, to avoid leaking filesystem layout.
            log.warn("Refusing to ingest '{}': it is not the file that was validated ({})", real, e.getMessage());
            throw new UploadFromPathException(MSG_UNAVAILABLE);
        }
    }

    /**
     * The filesystem identity for a name, read without following links; null if unsupported.
     */
    private static Object identityOf(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            log.warn("Could not close the source file after refusing it", e);
        }
    }

    private Path canonicaliseRoot(String configuredRoot) {
        if (configuredRoot == null || configuredRoot.trim().isEmpty()) {
            return null;
        }
        String trimmed = configuredRoot.trim();
        try {
            Path root = Paths.get(trimmed);
            if (!root.isAbsolute()) {
                log.warn("Ignoring allowed-paths entry '{}': it is not an absolute path", trimmed);
                return null;
            }
            Path real = root.toRealPath();
            if (!Files.isDirectory(real)) {
                log.warn("Ignoring allowed-paths entry '{}': it is not a directory", trimmed);
                return null;
            }
            return real;
        } catch (InvalidPathException | IOException e) {
            log.warn("Ignoring allowed-paths entry '{}': it cannot be resolved ({})", trimmed, e.getMessage());
            return null;
        }
    }

    /**
     * A validated source file held open; its length and content both come from the one open handle.
     */
    public static class OpenSourceFile implements Closeable {

        private final Path path;

        private final long size;

        private final FileChannel channel;

        private final InputStream inputStream;

        OpenSourceFile(Path path, long size, FileChannel channel) {
            this.path = path;
            this.size = size;
            this.channel = channel;
            this.inputStream = new BufferedInputStream(Channels.newInputStream(channel));
        }

        /**
         * @return the canonical path that was validated, for logging and for the optional delete
         */
        public Path getPath() {
            return path;
        }

        /**
         * @return the length in bytes, taken from the open handle
         */
        public long getSize() {
            return size;
        }

        /**
         * @return the content; readable once, and only until this object is closed
         */
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link UploadFromPathPathValidator}.
 * <p>
 * These are deliberately plain JUnit tests with no DSpace kernel and no Spring context: the
 * validator is the security boundary of the upload-from-path feature and must be cheap enough
 * to test exhaustively.
 */
public class UploadFromPathPathValidatorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File allowedRoot;

    private UploadFromPathPathValidator validator;

    /**
     * Build the fixture used by most tests: a single allowed root holding one ordinary file.
     *
     * @throws IOException if the fixture cannot be created
     */
    @Before
    public void setUp() throws IOException {
        allowedRoot = temporaryFolder.newFolder("allowed");
        validator = new UploadFromPathPathValidator(new String[] { allowedRoot.getAbsolutePath() });
    }

    @Test
    public void rejectsWhenAllowListEmpty() throws IOException {
        File file = newFileUnder(allowedRoot, "big.bin");
        UploadFromPathPathValidator noRoots = new UploadFromPathPathValidator(new String[0]);

        String message = rejectionMessage(noRoots, file.getAbsolutePath());
        assertTrue("expected the fail-closed message, got: " + message,
                   message.toLowerCase(Locale.ROOT).contains("not enabled"));
    }

    @Test
    public void rejectsWhenAllowListIsNull() throws IOException {
        File file = newFileUnder(allowedRoot, "big.bin");
        UploadFromPathPathValidator noRoots = new UploadFromPathPathValidator(null);

        String message = rejectionMessage(noRoots, file.getAbsolutePath());
        assertTrue("expected the fail-closed message, got: " + message,
                   message.toLowerCase(Locale.ROOT).contains("not enabled"));
    }

    @Test
    public void rejectsWhenEveryConfiguredRootIsUnresolvable() {
        UploadFromPathPathValidator broken =
            new UploadFromPathPathValidator(new String[] { new File(allowedRoot, "gone").getAbsolutePath() });

        String message = rejectionMessage(broken, new File(allowedRoot, "big.bin").getAbsolutePath());
        assertTrue("expected the fail-closed message, got: " + message,
                   message.toLowerCase(Locale.ROOT).contains("not enabled"));
    }

    @Test
    public void rejectsHttpUrl() {
        String message = rejectionMessage(validator, "http://example.org/big.zip");
        assertTrue("expected a message about URLs, got: " + message,
                   message.toLowerCase(Locale.ROOT).contains("url"));
    }

    @Test
    public void rejectsHttpsUrl() {
        String message = rejectionMessage(validator, "https://example.org/big.zip");
        assertTrue("expected a message about URLs, got: " + message,
                   message.toLowerCase(Locale.ROOT).contains("url"));
    }

    @Test
    public void rejectsFileUrl() {
        String message = rejectionMessage(validator, "file:///bigfiles/x.bin");
        assertTrue("expected a message about URLs, got: " + message,
                   message.toLowerCase(Locale.ROOT).contains("url"));
    }

    @Test
    public void rejectsRelativePath() {
        String message = rejectionMessage(validator, "bigfiles/x.bin");
        assertTrue("expected a message about absolute paths, got: " + message,
                   message.toLowerCase(Locale.ROOT).contains("absolute"));
    }

    /**
     * A NUL byte is rejected by every supported filesystem provider, so this reaches the
     * {@link java.nio.file.InvalidPathException} branch without depending on the host platform.
     */
    @Test
    public void rejectsUnparseablePath() {
        String message = rejectionMessage(validator,
                allowedRoot.getAbsolutePath() + File.separator + "big\0.bin");
        assertTrue("expected a message about the path being invalid, got: " + message,
                   message.toLowerCase(Locale.ROOT).contains("not a valid"));
    }

    @Test
    public void rejectsMissingFile() {
        rejectionMessage(validator, new File(allowedRoot, "nope.bin").getAbsolutePath());
    }

    @Test
    public void rejectsDirectory() {
        String message = rejectionMessage(validator, allowedRoot.getAbsolutePath());
        assertTrue("expected a message about regular files, got: " + message,
                   message.toLowerCase(Locale.ROOT).contains("regular file"));
    }

    @Test
    public void rejectsTraversalEscapingAllowedRoot() throws IOException {
        temporaryFolder.newFile("secret.txt");

        rejectionMessage(validator, allowedRoot.getAbsolutePath() + File.separator + ".."
                + File.separator + "secret.txt");
    }

    @Test
    public void rejectsSymlinkEscapingAllowedRoot() throws IOException {
        File secret = temporaryFolder.newFile("secret.bin");
        Path link = allowedRoot.toPath().resolve("link.bin");
        try {
            Files.createSymbolicLink(link, secret.toPath());
        } catch (IOException | UnsupportedOperationException e) {
            // Windows requires a privilege for this; skip rather than report a false failure.
            Assume.assumeNoException("symbolic links are not available on this worker", e);
        }

        rejectionMessage(validator, link.toAbsolutePath().toString());
    }

    /**
     * An attacker must not be able to use the error message to discover which paths exist on the
     * server: a real file outside the allow-list and a path that is simply absent must look the same.
     *
     * @throws IOException if the fixture cannot be created
     */
    @Test
    public void outsideAllowedRootIsIndistinguishableFromMissingFile() throws IOException {
        File outside = temporaryFolder.newFile("outside.bin");
        File missing = new File(allowedRoot, "nope.bin");

        assertEquals(rejectionMessage(validator, missing.getAbsolutePath()),
                     rejectionMessage(validator, outside.getAbsolutePath()));
    }

    @Test
    public void messageDoesNotLeakAllowList() throws IOException {
        File secondRoot = temporaryFolder.newFolder("second");
        UploadFromPathPathValidator twoRoots = new UploadFromPathPathValidator(
            new String[] { allowedRoot.getAbsolutePath(), secondRoot.getAbsolutePath() });
        File outside = temporaryFolder.newFile("outside.bin");

        String message = rejectionMessage(twoRoots, outside.getAbsolutePath());
        assertFalse("message leaks an allow-list root: " + message,
                    message.contains(allowedRoot.getAbsolutePath()));
        assertFalse("message leaks an allow-list root: " + message,
                    message.contains(secondRoot.getAbsolutePath()));
    }

    @Test
    public void acceptsFileDirectlyInAllowedRoot() throws Exception {
        File file = newFileUnder(allowedRoot, "big.bin");

        assertEquals(file.toPath().toRealPath(), validator.validate(file.getAbsolutePath()));
    }

    @Test
    public void acceptsFileInNestedSubdirectory() throws Exception {
        File nested = new File(allowedRoot, "a" + File.separator + "b");
        assertTrue(nested.mkdirs());
        File file = newFileUnder(nested, "c.bin");

        assertEquals(file.toPath().toRealPath(), validator.validate(file.getAbsolutePath()));
    }

    @Test
    public void acceptsWhenMultipleRootsConfiguredAndSecondMatches() throws Exception {
        File secondRoot = temporaryFolder.newFolder("second");
        UploadFromPathPathValidator twoRoots = new UploadFromPathPathValidator(
            new String[] { allowedRoot.getAbsolutePath(), secondRoot.getAbsolutePath() });
        File file = newFileUnder(secondRoot, "big.bin");

        assertEquals(file.toPath().toRealPath(), twoRoots.validate(file.getAbsolutePath()));
    }

    @Test
    public void unresolvableConfiguredRootIsDroppedNotFatal() throws Exception {
        UploadFromPathPathValidator mixed = new UploadFromPathPathValidator(new String[] {
            new File(allowedRoot, "does-not-exist").getAbsolutePath(),
            "   ",
            allowedRoot.getAbsolutePath(),
        });
        File file = newFileUnder(allowedRoot, "big.bin");

        assertEquals(file.toPath().toRealPath(), mixed.validate(file.getAbsolutePath()));
    }

    @Test
    public void acceptsPathContainingHarmlessTraversalInsideAllowedRoot() throws Exception {
        File nested = new File(allowedRoot, "a");
        assertTrue(nested.mkdirs());
        File file = newFileUnder(allowedRoot, "big.bin");

        String traversed = nested.getAbsolutePath() + File.separator + ".." + File.separator + "big.bin";
        assertEquals(file.toPath().toRealPath(), validator.validate(traversed));
    }

    /**
     * A root that is a sibling with a common textual prefix must not be treated as containing
     * the path: containment is compared by path element, not by string prefix.
     *
     * @throws IOException if the fixture cannot be created
     */
    @Test
    public void rejectsSiblingDirectorySharingATextualPrefix() throws IOException {
        File sibling = temporaryFolder.newFolder("allowed-evil");
        File file = newFileUnder(sibling, "big.bin");

        rejectionMessage(validator, file.getAbsolutePath());
    }

    @Test(expected = IllegalStateException.class)
    public void blankValueIsAProgrammingError() {
        validator.validate("   ");
    }

    @Test(expected = IllegalStateException.class)
    public void nullValueIsAProgrammingError() {
        validator.validate(null);
    }

    private File newFileUnder(File parent, String name) throws IOException {
        File file = new File(parent, name);
        Files.write(file.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String rejectionMessage(UploadFromPathPathValidator target, String rawValue) {
        try {
            Path accepted = target.validate(rawValue);
            fail("expected '" + rawValue + "' to be refused, but it resolved to " + accepted);
            return null;
        } catch (UploadFromPathException e) {
            return e.getMessage();
        }
    }
}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.file.PathUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.authorize.service.ResourcePolicyService;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.GroupService;
import org.dspace.license.service.CreativeCommonsService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.workflow.WorkflowItem;
import org.dspace.workflow.WorkflowItemService;
import org.dspace.workflow.factory.WorkflowServiceFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration testing for the DataShare CC licence hook on the SAF batch import
 * ({@link org.dspace.app.itemimport.ItemImport}, i.e. the <code>dspace import</code> CLI).
 *
 * <p>DataShare (DSpace 6) automatically attached the Creative Commons Attribution 4.0 licence to every
 * batch-imported item whose <code>dc.rights</code> announced that licence, producing exactly the same
 * CC-LICENSE bundle that the web submission produces today via
 * <code>DatashareLicenseStep</code>. That behaviour was lost in the DSpace 8 migration; these tests pin
 * it back down, on both branches of
 * {@code ItemImportServiceImpl.addItem()}:</p>
 * <ul>
 *   <li><b>--workflow</b> (what production actually runs): with reviewers the item is still an in-progress
 *       workflow item when the hook fires; without reviewers the workflow archives it immediately, so even
 *       here the item can already be installed;</li>
 *   <li><b>plain --add</b>: {@code installItem()} has already run before the hook fires.</li>
 * </ul>
 *
 * <p>On both archived paths the tests assert that the new bundle and bitstream are readable by Anonymous,
 * because that - not any particular implementation - is what the customer cares about: the licence must
 * download for a logged-out visitor. The hook itself does <em>not</em> grant those policies; DSpace 8
 * supplies them through {@code ItemServiceImpl.addBundle()} and {@code BundleServiceImpl.addBitstream()}.
 * An earlier revision re-applied the collection defaults by hand and was removed once these very
 * assertions were shown to pass without it.</p>
 *
 * <p>One test goes the other way and pins that the hook does <em>not</em> hand out read access: an item
 * that lands under embargo must keep its licence bitstream embargoed too.</p>
 *
 * <p>The remaining tests pin the guards: no <code>dc.rights</code>, a non-matching <code>dc.rights</code>,
 * a package that already ships its own CC-LICENSE bundle, the feature switched off, a validate-only
 * (<code>-v</code>) run - with a usable licence file and with a broken one - a metadata-only
 * (<code>-x</code>) run, and - the cases that used to fail silently across a whole batch - a misconfigured
 * licence file and a blanked-out trigger value.</p>
 *
 * <p>This lives in {@code uk.ac.ed.datashare} rather than in the vanilla
 * {@code org.dspace.app.itemimport.ItemImportCLIIT} on purpose: {@code ItemImportCLIIT} is an upstream
 * file that is rebased on every DSpace upgrade, and keeping the UoE-specific coverage out of it keeps
 * that rebase clean (same reasoning as keeping the production diff in {@code ItemImportServiceImpl}
 * down to three calls).</p>
 */
public class DatashareItemImportCcLicenseIT extends AbstractIntegrationTestWithDatabase {

    private static final String ITEM_TITLE = "A Tale of Two Cities";
    private static final String ITEM_DIR_NAME = "item_000";

    /**
     * The canonical dc.rights value that triggers the licence, identical to the one hardcoded in
     * DatashareLicenseStep for the web submission path.
     */
    private static final String CC_BY_RIGHTS = "Creative Commons Attribution 4.0 International Public License";

    /** A second, British-spelling trigger value, used only to prove the trigger list is configurable. */
    private static final String CC_BY_RIGHTS_BRITISH =
            "Creative Commons Attribution 4.0 International Public Licence";

    private static final String CC_LICENSE_ENABLED = "itemimport.cc-license.enabled";
    private static final String CC_LICENSE_RIGHTS_VALUE = "itemimport.cc-license.rights-value";
    private static final String CC_LICENSE_FILE = "itemimport.cc-license.file";

    private static final String PACKAGE_LICENCE_TEXT = "LICENCE SHIPPED INSIDE THE SAF PACKAGE";
    private static final String CUSTOM_LICENCE_TEXT = "A DIFFERENT LICENCE, FROM A CONFIGURED FILE";

    private static final String PAYLOAD_FILE_NAME = "data.txt";

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
    private final AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
    private final ResourcePolicyService resourcePolicyService =
            AuthorizeServiceFactory.getInstance().getResourcePolicyService();
    private final GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();
    private final WorkflowItemService<? extends WorkflowItem> workflowItemService =
            WorkflowServiceFactory.getInstance().getWorkflowItemService();
    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    private Collection collection;
    private Path tempDir;
    private Path workDir;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        context.restoreAuthSystemState();

        tempDir = Files.createTempDirectory("safCcLicenseTest");
        File file = new File(configurationService.getProperty("org.dspace.app.batchitemimport.work.dir"));
        if (!file.exists()) {
            Files.createDirectory(Path.of(file.getAbsolutePath()));
        }
        workDir = Path.of(file.getAbsolutePath());
    }

    @After
    @Override
    public void destroy() throws Exception {
        // Workflow items produced by the importer are not tracked by the test builders. Drop them (and
        // their pool tasks) here, otherwise deleting the collection's workflow group hits a foreign key
        // violation and the leftovers poison every later test in this class.
        context.turnOffAuthorisationSystem();
        Collection reloaded = context.reloadEntity(collection);
        if (reloaded != null) {
            workflowItemService.deleteByCollection(context, reloaded);
            context.commit();
        }
        context.restoreAuthSystemState();

        PathUtils.deleteOnExit(tempDir);
        for (Path path : Files.list(workDir).collect(Collectors.toList())) {
            PathUtils.deleteOnExit(path);
        }
        super.destroy();
    }

    /**
     * Production case: <code>dspace import --add --workflow</code> (see
     * <code>UoE/saf-import/batch_import.sh</code>) with the canonical dc.rights. The item never reaches
     * <code>installItem()</code> during the import, so the hook must still produce the CC-LICENSE bundle
     * on the in-progress workflow item.
     */
    @Test
    public void importItemBySafWithWorkflowCreatesCcLicenseBundle() throws Exception {
        context.turnOffAuthorisationSystem();
        // a reviewer keeps the item in the workflow instead of auto-archiving it, so this really
        // exercises the "item not yet installed" branch of addItem()
        collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Reviewed Collection")
                .withWorkflowGroup("reviewer", admin)
                .build();
        context.restoreAuthSystemState();

        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir, "-w");

        Item item = findWorkflowItem();
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
        assertArrayEquals("the CC-LICENSE bitstream must hold the configured licence file",
                Files.readAllBytes(defaultLicenceFile()), bytesOf(licence));
        assertRightsMetadataUntouched(item, CC_BY_RIGHTS);
    }

    /**
     * <code>--workflow</code> into a collection with no reviewers: the workflow finds no valid step and
     * archives the item straight away inside <code>workflowService.start()</code>, so even on the
     * <code>--workflow</code> path the item can already be installed by the time the hook runs. The
     * licence must still end up readable by anonymous visitors.
     */
    @Test
    public void importItemBySafWithWorkflowThatAutoArchivesCreatesCcLicenseReadableByAnonymous() throws Exception {
        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir, "-w");

        Item item = findArchivedItem();
        Bundle ccBundle = assertSingleCcLicenseBundle(item);
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
        assertAnonymousReadPolicy("CC-LICENSE bundle", ccBundle);
        assertAnonymousReadPolicy("CC-LICENSE bitstream", licence);
        assertRightsMetadataUntouched(item, CC_BY_RIGHTS);
    }

    /**
     * Plain <code>dspace import --add</code>: the item is installed (archived) before the hook runs, so
     * <code>installItem()</code>'s one pass over the collection defaults has already happened and cannot
     * cover a bundle created afterwards. It does not need to: {@code ItemServiceImpl.addBundle()} inherits
     * the item's policies onto the new bundle and {@code BundleServiceImpl.addBitstream()} then applies
     * the collection's DEFAULT_BITSTREAM_READ, so the licence is readable by anonymous visitors without
     * the hook touching authorisation at all.
     */
    @Test
    public void importItemBySafWithoutWorkflowCreatesCcLicenseReadableByAnonymous() throws Exception {
        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir);

        Item item = findArchivedItem();
        Bundle ccBundle = assertSingleCcLicenseBundle(item);
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
        assertArrayEquals("the CC-LICENSE bitstream must hold the configured licence file",
                Files.readAllBytes(defaultLicenceFile()), bytesOf(licence));
        assertAnonymousReadPolicy("CC-LICENSE bundle", ccBundle);
        assertAnonymousReadPolicy("CC-LICENSE bitstream", licence);
        assertRightsMetadataUntouched(item, CC_BY_RIGHTS);
    }

    /**
     * The other side of the two tests above, and the reason the hook must keep its hands off authorisation
     * altogether. Here the collection only lets Anonymous read its items from a future date, so every item
     * it archives is embargoed from the moment {@code installItem()} clones that default onto it - which is
     * precisely the state {@code BundleServiceImpl.addBitstream()} looks for when it decides <em>not</em> to
     * apply the collection's DEFAULT_BITSTREAM_READ. The collection's bitstream default is deliberately left
     * unrestricted, because that is what gives the assertion teeth: the licence must take the item's
     * embargo, not the collection's open bitstream default.
     *
     * <p>An earlier revision of the hook re-applied those collection defaults to the licence by hand. On
     * every archived path above that was dead code, but here it was not: it would have published the
     * licence of an embargoed item to the world on the day of the import. The helper is gone; this test is
     * what stops it coming back.</p>
     */
    @Test
    public void importItemBySafIntoAnEmbargoedCollectionLeavesTheCcLicenceEmbargoed() throws Exception {
        embargoTheCollectionsItems();
        Path safDir = createSafDirWithPayload(CC_BY_RIGHTS);

        performImport(safDir);

        Item item = findArchivedItem();
        Bundle ccBundle = assertSingleCcLicenseBundle(item);
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
        assertEmbargoedReadPolicy("CC-LICENSE bundle", ccBundle);
        assertEmbargoedReadPolicy("CC-LICENSE bitstream", licence);
    }

    /**
     * No dc.rights at all - the DSpace 6 code NPE'd here. The import must succeed and simply create no
     * CC-LICENSE bundle.
     */
    @Test
    public void importItemBySafWithoutRightsCreatesNoCcLicenseBundle() throws Exception {
        Path safDir = createSafDir(null);

        performImport(safDir);

        Item item = findArchivedItem();
        assertEquals("dc.title must have been imported", ITEM_TITLE, item.getName());
        assertNoCcLicenseBundle(item);
    }

    /**
     * A dc.rights value that is not one of the configured triggers must not produce a licence.
     */
    @Test
    public void importItemBySafWithNonMatchingRightsCreatesNoCcLicenseBundle() throws Exception {
        Path safDir = createSafDir("All rights reserved");

        performImport(safDir);

        Item item = findArchivedItem();
        assertNoCcLicenseBundle(item);
    }

    /**
     * The SAF package always wins: if the contents file already declares a CC-LICENSE bundle the hook
     * must skip, leaving exactly one bundle holding the package's own bytes.
     */
    @Test
    public void importItemBySafWithCcLicenseInPackageKeepsThePackageLicence() throws Exception {
        Path safDir = createSafDir(CC_BY_RIGHTS);
        Path itemDir = safDir.resolve(ITEM_DIR_NAME);
        Files.writeString(itemDir.resolve("contents"), "license_text\tbundle:CC-LICENSE");
        Files.writeString(itemDir.resolve("license_text"), PACKAGE_LICENCE_TEXT);

        performImport(safDir);

        Item item = findArchivedItem();
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertEquals("the package's own licence bytes must survive", PACKAGE_LICENCE_TEXT, contentOf(licence));
    }

    /**
     * Switched off by configuration: nothing is created, even for the canonical dc.rights.
     */
    @Test
    public void importItemBySafWithFeatureDisabledCreatesNoCcLicenseBundle() throws Exception {
        configurationService.setProperty(CC_LICENSE_ENABLED, false);
        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir);

        Item item = findArchivedItem();
        assertNoCcLicenseBundle(item);
    }

    /**
     * <code>-v</code> / <code>--validate</code> is a dry run (it sets <code>isTest</code>): no item and
     * therefore no licence may be created, and the run must not blow up.
     */
    @Test
    public void validateOnlyImportBySafCreatesNothing() throws Exception {
        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir, "-v");

        assertTrue("a validate-only run must not archive an item", findArchivedItems().isEmpty());
        assertTrue("a validate-only run must not create a workflow item",
                workflowItemService.findByCollection(context, collection).isEmpty());
    }

    /**
     * The same dry run on a host whose licence file is not there. A <code>-v</code> run imports nothing, so
     * the licence configuration cannot possibly harm it and refusing it would be plain wrong - and wrong in
     * the customer's face, because <code>UoE/saf-import/batch_import.sh</code> maps its own documented "-t"
     * test switch onto <code>-v</code>. Rehearsing an import is exactly when an operator is most likely to
     * be on a host where cc-by.license has not been deployed yet, and that rehearsal must still tell them
     * whether their SAF package is sound.
     */
    @Test
    public void validateOnlyImportBySafWithMissingLicenceFileStillRuns() throws Exception {
        Path missingLicence = tempDir.resolve("no-such-directory").resolve("cc-by.license");
        configurationService.setProperty(CC_LICENSE_FILE, missingLicence.toString());
        Path safDir = createSafDir(CC_BY_RIGHTS, 3);

        performImport(safDir, "-v");

        assertTrue("a validate-only run must not archive an item", findArchivedItems().isEmpty());
        assertTrue("a validate-only run must not create a workflow item",
                workflowItemService.findByCollection(context, collection).isEmpty());
    }

    /**
     * <code>-x</code> / <code>--exclude-bitstreams</code> is a metadata-only import: the package's own
     * bitstreams are skipped, so attaching the licence would make it the item's <em>only</em> bitstream -
     * a metadata-only item that somehow has a file. It would also defeat the "package wins" guard, since
     * the package's own <code>bundle:CC-LICENSE</code> line is skipped too and the guard would find
     * nothing to protect. So the hook must not run at all.
     */
    @Test
    public void importItemBySafExcludingBitstreamsCreatesNoCcLicenseBundle() throws Exception {
        Path safDir = createSafDirWithPayload(CC_BY_RIGHTS);

        performImport(safDir, "-x");

        Item item = findArchivedItem();
        assertEquals("dc.title must have been imported", ITEM_TITLE, item.getName());
        assertNoCcLicenseBundle(item);
        assertTrue("a metadata-only import must not produce any bitstream at all",
                item.getBundles().stream().allMatch(bundle -> bundle.getBitstreams().isEmpty()));
    }

    /**
     * A misconfigured licence file is not a per-item problem, it is the same problem for every item in the
     * run. Reporting it per item would let a batch of N finish "successfully" with N licence-less items
     * and N mapfile lines - and <code>--resume</code> would then skip exactly those items, so recovery
     * would need a hand-written script. The run must therefore be refused before the first item is
     * created and before the mapfile is written, with a message naming both the path and the config key.
     */
    @Test
    public void importItemBySafWithMissingLicenceFileAbortsBeforeAnyItemIsImported() throws Exception {
        Path missingLicence = tempDir.resolve("no-such-directory").resolve("cc-by.license");
        configurationService.setProperty(CC_LICENSE_FILE, missingLicence.toString());
        Path safDir = createSafDir(CC_BY_RIGHTS, 3);

        assertRunRefused(safDir, missingLicence);
    }

    /**
     * A 0-byte licence file - an interrupted copy, or a template that rendered to nothing - must be
     * refused just as loudly. Silently depositing it would be worse than failing: the empty bundle then
     * satisfies the "package wins" guard forever, so no later re-import would ever repair those items.
     * Note the check is a real read rather than {@code File.canRead()}, which is unreliable against NTFS
     * ACLs and says nothing at all about the content.
     */
    @Test
    public void importItemBySafWithEmptyLicenceFileAbortsBeforeAnyItemIsImported() throws Exception {
        Path emptyLicence = Files.createFile(tempDir.resolve("empty-cc.license"));
        configurationService.setProperty(CC_LICENSE_FILE, emptyLicence.toString());
        Path safDir = createSafDir(CC_BY_RIGHTS, 3);

        assertRunRefused(safDir, emptyLicence);
    }

    /**
     * A trigger value that is present but blank is the same misconfiguration wearing a different hat, and a
     * likelier one: <code>itemimport.cc-license.rights-value =</code> in a local.cfg reads as "switch the
     * trigger off", but ConfigurationService falls back to the coded default only when the key is
     * <em>absent</em>, so a key that is present and empty counts as configured and leaves no usable trigger
     * at all. Nothing would then ever match, and the whole run would come out licence-less - so it is
     * refused up front, naming the key, exactly as for an unusable licence file.
     */
    @Test
    public void importItemBySafWithBlankRightsValueAbortsBeforeAnyItemIsImported() throws Exception {
        configurationService.setProperty(CC_LICENSE_RIGHTS_VALUE, "");
        Path safDir = createSafDir(CC_BY_RIGHTS, 3);

        String message = assertRunRefused(safDir);
        assertTrue("the failure must name the configuration key to fix, was: " + message,
                message.contains(CC_LICENSE_RIGHTS_VALUE));
    }

    /**
     * The boundary of the check above: a blank <em>among</em> the configured trigger values is untidy, not
     * fatal. One usable value is left, so the run must go ahead and licence the items that match it rather
     * than refuse the whole batch over a stray entry.
     */
    @Test
    public void importItemBySafWithBlankAmongTheConfiguredRightsValuesUsesTheRest() throws Exception {
        configurationService.setProperty(CC_LICENSE_RIGHTS_VALUE, new String[] { "", CC_BY_RIGHTS });
        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir);

        Item item = findArchivedItem();
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
    }

    /**
     * The trigger list is configurable and holds multiple values, so accepting e.g. the British spelling
     * later is a configuration change and not a code change.
     */
    @Test
    public void importItemBySafWithConfiguredAlternativeRightsValueCreatesCcLicenseBundle() throws Exception {
        configurationService.setProperty(CC_LICENSE_RIGHTS_VALUE,
                new String[] { CC_BY_RIGHTS, CC_BY_RIGHTS_BRITISH });
        Path safDir = createSafDir(CC_BY_RIGHTS_BRITISH);

        performImport(safDir);

        Item item = findArchivedItem();
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
    }

    /**
     * The licence file is configurable too; the bitstream must hold the bytes of the configured file.
     */
    @Test
    public void importItemBySafUsesTheConfiguredLicenceFile() throws Exception {
        Path licenceFile = Files.writeString(tempDir.resolve("custom-cc.license"), CUSTOM_LICENCE_TEXT);
        configurationService.setProperty(CC_LICENSE_FILE, licenceFile.toString());
        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir);

        Item item = findArchivedItem();
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
        assertEquals("the configured licence file must be the one that is deposited",
                CUSTOM_LICENCE_TEXT, contentOf(licence));
    }

    /**
     * Run the import and assert it was refused up front: nothing imported, nothing left in the workflow,
     * and - the point of doing this before the loop - no mapfile, so a later <code>--resume</code> cannot
     * skip the items that never got their licence.
     *
     * @return the failure message, for the caller to check names whatever needs fixing
     */
    private String assertRunRefused(Path safDir) throws Exception {
        Exception thrown = assertThrows(Exception.class, () -> performImport(safDir));

        assertTrue("no item may have been imported", findArchivedItems().isEmpty());
        assertTrue("no item may have been left in the workflow",
                workflowItemService.findByCollection(context, collection).isEmpty());
        assertFalse("the mapfile must not have been written, or --resume would skip the failed items",
                Files.exists(mapFile()));
        return String.valueOf(thrown.getMessage());
    }

    /**
     * As above, for the case where the licence file itself is the thing that cannot be used: the message
     * must name both the path and the key that points at it.
     */
    private void assertRunRefused(Path safDir, Path badLicenceFile) throws Exception {
        String message = assertRunRefused(safDir);

        assertTrue("the failure must name the offending licence file, was: " + message,
                message.contains(badLicenceFile.toString()));
        assertTrue("the failure must name the configuration key to fix, was: " + message,
                message.contains(CC_LICENSE_FILE));
    }

    /**
     * Put the test collection's items under embargo: from now on it only lets Anonymous read what it
     * archives from a date ten years out, which {@code installItem()} clones onto every item it installs.
     *
     * <p>Only the item default is touched. The collection's DEFAULT_BITSTREAM_READ stays unrestricted on
     * purpose - it is the open policy that the removed helper would have applied to the licence, so leaving
     * it open is what makes {@link #assertEmbargoedReadPolicy} able to tell the two behaviours apart.</p>
     */
    private void embargoTheCollectionsItems() throws Exception {
        context.turnOffAuthorisationSystem();
        Date liftDate = Date.from(LocalDate.now().plusYears(10).atStartOfDay(ZoneOffset.UTC).toInstant());
        List<ResourcePolicy> defaultItemRead =
                authorizeService.getPoliciesActionFilter(context, collection, Constants.DEFAULT_ITEM_READ);
        assertFalse("the test collection must have a default item READ policy to embargo",
                defaultItemRead.isEmpty());
        for (ResourcePolicy policy : defaultItemRead) {
            policy.setStartDate(liftDate);
            resourcePolicyService.update(context, policy);
        }
        context.restoreAuthSystemState();
        context.commit();
    }

    /**
     * Build a one-item SAF package under a fresh source directory.
     *
     * @param dcRights the dc.rights value to write, or null to omit dc.rights entirely
     * @return the SAF source directory, ready to be passed to the importer with -s
     */
    private Path createSafDir(String dcRights) throws Exception {
        return createSafDir(dcRights, 1);
    }

    /**
     * Build a SAF package of the given size under a fresh source directory. More than one item matters
     * for the misconfiguration tests: the damage being guarded against is "all N items silently lose
     * their licence", which a single-item package cannot show.
     *
     * @param dcRights  the dc.rights value to write into every item, or null to omit dc.rights entirely
     * @param itemCount how many item directories to create
     * @return the SAF source directory, ready to be passed to the importer with -s
     */
    private Path createSafDir(String dcRights, int itemCount) throws Exception {
        Path safDir = Files.createDirectory(tempDir.resolve("test"));
        for (int i = 0; i < itemCount; i++) {
            Path itemDir = Files.createDirectory(safDir.resolve(String.format("item_%03d", i)));
            StringBuilder dublinCore = new StringBuilder("<dublin_core>\n")
                    .append("    <dcvalue element=\"title\" qualifier=\"none\">")
                    .append(ITEM_TITLE)
                    .append("</dcvalue>\n")
                    .append("    <dcvalue element=\"date\" qualifier=\"issued\">1990</dcvalue>\n");
            if (dcRights != null) {
                dublinCore.append("    <dcvalue element=\"rights\" qualifier=\"none\">")
                        .append(dcRights)
                        .append("</dcvalue>\n");
            }
            dublinCore.append("</dublin_core>");
            Files.writeString(itemDir.resolve("dublin_core.xml"), dublinCore.toString());
        }
        return safDir;
    }

    /**
     * A one-item SAF package that also ships a bitstream, i.e. what a real DataShare deposit looks like.
     */
    private Path createSafDirWithPayload(String dcRights) throws Exception {
        Path safDir = createSafDir(dcRights);
        Path itemDir = safDir.resolve(ITEM_DIR_NAME);
        Files.writeString(itemDir.resolve("contents"), PAYLOAD_FILE_NAME);
        Files.writeString(itemDir.resolve(PAYLOAD_FILE_NAME), "the deposited research data");
        return safDir;
    }

    /**
     * Run "dspace import --add" over the given SAF directory into the test collection.
     *
     * @param safDir    the SAF source directory
     * @param extraArgs any extra CLI switches, e.g. "-w", "-v" or "-x"
     */
    private void performImport(Path safDir, String... extraArgs) throws Exception {
        List<String> args = new ArrayList<>(List.of("import", "-a",
                "-e", admin.getEmail(),
                "-c", collection.getID().toString(),
                "-s", safDir.toString(),
                "-m", mapFile().toString()));
        args.addAll(List.of(extraArgs));
        runDSpaceScript(args.toArray(new String[0]));
    }

    /**
     * The mapfile the importer is told to write; the importer creates it, so it must not exist up front.
     */
    private Path mapFile() {
        return tempDir.resolve("mapfile.out");
    }

    /**
     * The licence file the hook uses when nothing is configured: the same file DatashareLicenseStep
     * deposits on the web submission path.
     */
    private Path defaultLicenceFile() {
        return Path.of(configurationService.getProperty("dspace.dir"), "config", "cc-by.license");
    }

    /**
     * All archived items in the test collection that carry the imported title.
     */
    private List<Item> findArchivedItems() throws Exception {
        List<Item> items = new ArrayList<>();
        Iterator<Item> iterator = itemService.findByMetadataField(context, "dc", "title", null, ITEM_TITLE);
        while (iterator.hasNext()) {
            Item item = iterator.next();
            if (collection.equals(item.getOwningCollection())) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * The single archived item produced by the import.
     */
    private Item findArchivedItem() throws Exception {
        List<Item> items = findArchivedItems();
        assertEquals("the import must have archived exactly one item", 1, items.size());
        return items.get(0);
    }

    /**
     * The item of the single in-progress workflow item produced by a --workflow import.
     */
    private Item findWorkflowItem() throws Exception {
        List<? extends WorkflowItem> workflowItems = workflowItemService.findByCollection(context, collection);
        assertEquals("the import must have created exactly one workflow item", 1, workflowItems.size());
        return workflowItems.get(0).getItem();
    }

    private void assertNoCcLicenseBundle(Item item) throws Exception {
        assertTrue("no CC-LICENSE bundle must be created",
                itemService.getBundles(item, CreativeCommonsService.CC_BUNDLE_NAME).isEmpty());
    }

    private Bundle assertSingleCcLicenseBundle(Item item) throws Exception {
        List<Bundle> bundles = itemService.getBundles(item, CreativeCommonsService.CC_BUNDLE_NAME);
        assertEquals("the item must have exactly one CC-LICENSE bundle", 1, bundles.size());
        return bundles.get(0);
    }

    private Bitstream assertSingleCcLicenseBitstream(Item item) throws Exception {
        List<Bitstream> bitstreams = assertSingleCcLicenseBundle(item).getBitstreams();
        assertEquals("the CC-LICENSE bundle must hold exactly one bitstream", 1, bitstreams.size());
        return bitstreams.get(0);
    }

    /**
     * The bundle/bitstream shape CreativeCommonsService.setLicense() produces, i.e. exactly what the web
     * submission (DatashareLicenseStep) leaves behind.
     */
    private void assertCcLicenseBitstreamShape(Bitstream bitstream) throws Exception {
        assertEquals("license_text", bitstream.getName());
        assertEquals("License", bitstream.getFormat(context).getShortDescription());
        assertEquals("org.dspace.license.CreativeCommons", bitstream.getSource());
    }

    /**
     * DSpace 6 rewrote the rights metadata when it attached the licence. The DSpace 8 web submission does
     * not, so neither may we: a curator's dc.rights must come back out of the importer untouched, and no
     * dc.rights.uri may be invented (that field is what would later let the UI strip the licence again).
     */
    private void assertRightsMetadataUntouched(Item item, String expected) {
        List<MetadataValue> rights = itemService.getMetadataByMetadataString(item, "dc.rights");
        assertEquals("dc.rights must survive the import unchanged", 1, rights.size());
        assertEquals("dc.rights must survive the import unchanged", expected, rights.get(0).getValue());
        assertTrue("dc.rights.uri must not be invented by the import",
                itemService.getMetadataByMetadataString(item, "dc.rights.uri").isEmpty());
    }

    /**
     * Exactly one READ policy, for Anonymous, inherited from the owning collection's defaults. Asserting
     * the count as well as the group is what keeps this honest: it would still pass if some future change
     * granted the policy a second time, but it pins that the object is readable by exactly the audience
     * the collection says it should be, and by no one else.
     */
    private void assertAnonymousReadPolicy(String what, DSpaceObject dso) throws Exception {
        Group anonymous = groupService.findByName(context, Group.ANONYMOUS);
        List<ResourcePolicy> policies = authorizeService.getPoliciesActionFilter(context, dso, Constants.READ);
        assertEquals("the " + what + " must carry exactly one READ policy", 1, policies.size());
        assertEquals("the " + what + " must be readable by Anonymous, or the licence 403s for visitors",
                anonymous, policies.get(0).getGroup());
        assertEquals("the " + what + "'s READ policy must be inherited from the collection defaults",
                ResourcePolicy.TYPE_INHERITED, policies.get(0).getRpType());
    }

    /**
     * The mirror image of {@link #assertAnonymousReadPolicy}: still exactly one inherited Anonymous READ
     * policy, but one that has not started yet, i.e. the item's embargo has come down onto the licence with
     * it. Asserting the count as well would not be enough on its own - the helper that was removed replaced
     * the READ policy rather than adding to it, so the discriminating assertion is that the policy is not
     * in force today.
     */
    private void assertEmbargoedReadPolicy(String what, DSpaceObject dso) throws Exception {
        Group anonymous = groupService.findByName(context, Group.ANONYMOUS);
        List<ResourcePolicy> policies = authorizeService.getPoliciesActionFilter(context, dso, Constants.READ);
        assertEquals("the " + what + " must carry exactly one READ policy", 1, policies.size());
        assertEquals("the " + what + " must carry the Anonymous READ policy inherited from the item",
                anonymous, policies.get(0).getGroup());
        assertNotNull("the " + what + " must keep the item's embargo start date",
                policies.get(0).getStartDate());
        assertFalse("the " + what + " must not be readable while the item's embargo runs",
                resourcePolicyService.isDateValid(policies.get(0)));
    }

    private byte[] bytesOf(Bitstream bitstream) throws Exception {
        context.turnOffAuthorisationSystem();
        try (InputStream inputStream = bitstreamService.retrieve(context, bitstream)) {
            return inputStream.readAllBytes();
        } finally {
            context.restoreAuthSystemState();
        }
    }

    private String contentOf(Bitstream bitstream) throws Exception {
        return new String(bytesOf(bitstream), StandardCharsets.UTF_8);
    }
}

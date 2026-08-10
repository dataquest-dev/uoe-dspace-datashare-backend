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
 * Integration tests for the CC licence hook on the SAF batch import ({@code dspace import}): both
 * import paths, the guards that suppress the licence, the read policies the licence inherits, and the
 * up-front refusal of an unusable configuration.
 */
public class DatashareItemImportCcLicenseIT extends AbstractIntegrationTestWithDatabase {

    private static final String ITEM_TITLE = "A Tale of Two Cities";
    private static final String ITEM_DIR_NAME = "item_000";

    /** The canonical dc.rights value that triggers the licence, as matched by the web submission step. */
    private static final String CC_BY_RIGHTS = "Creative Commons Attribution 4.0 International Public License";

    /** The other shipped trigger: the spelling real SAF packages carry. */
    private static final String CC_BY_RIGHTS_BRITISH =
            "Creative Commons Attribution 4.0 International licence";

    /** An arbitrary wording, used only to prove the trigger list is configurable. */
    private static final String CC_BY_RIGHTS_CUSTOM = "Our Own Licence Wording";

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
        // The test builders do not track workflow items, and leaving them makes deleting the
        // collection's workflow group hit a foreign key violation in every later test.
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

    @Test
    public void importItemBySafWithWorkflowCreatesCcLicenseBundle() throws Exception {
        context.turnOffAuthorisationSystem();
        // a reviewer keeps the item in the workflow, so the hook runs before installItem()
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

    /** No reviewers, so the workflow archives the item at once and the hook runs after installItem(). */
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

    @Test
    public void importItemBySafWithoutRightsCreatesNoCcLicenseBundle() throws Exception {
        Path safDir = createSafDir(null);

        performImport(safDir);

        Item item = findArchivedItem();
        assertEquals("dc.title must have been imported", ITEM_TITLE, item.getName());
        assertNoCcLicenseBundle(item);
    }

    @Test
    public void importItemBySafWithNonMatchingRightsCreatesNoCcLicenseBundle() throws Exception {
        Path safDir = createSafDir("All rights reserved");

        performImport(safDir);

        Item item = findArchivedItem();
        assertNoCcLicenseBundle(item);
    }

    @Test
    public void importItemBySafWithCcLicenseInPackageKeepsThePackageLicence() throws Exception {
        Path safDir = createSafDir(CC_BY_RIGHTS);
        Path itemDir = safDir.resolve(ITEM_DIR_NAME);
        // a contents line is tab-separated; with a space the whole line reads as one filename
        Files.writeString(itemDir.resolve("contents"), "license_text\tbundle:CC-LICENSE");
        Files.writeString(itemDir.resolve("license_text"), PACKAGE_LICENCE_TEXT);

        performImport(safDir);

        Item item = findArchivedItem();
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertEquals("the package's own licence bytes must survive", PACKAGE_LICENCE_TEXT, contentOf(licence));
    }

    @Test
    public void importItemBySafWithFeatureDisabledCreatesNoCcLicenseBundle() throws Exception {
        configurationService.setProperty(CC_LICENSE_ENABLED, false);
        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir);

        Item item = findArchivedItem();
        assertNoCcLicenseBundle(item);
    }

    /** {@code -v} / {@code --validate} sets isTest, so the run is a rehearsal that creates nothing. */
    @Test
    public void validateOnlyImportBySafCreatesNothing() throws Exception {
        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir, "-v");

        assertTrue("a validate-only run must not archive an item", findArchivedItems().isEmpty());
        assertTrue("a validate-only run must not create a workflow item",
                workflowItemService.findByCollection(context, collection).isEmpty());
    }

    /** A rehearsal attaches no licence, so an unusable licence file must not stop it checking the package. */
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

    /** {@code -x} skips the package's own bitstreams, so the licence must not become the item's only one. */
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

    /** An unusable licence file is not per-item: unchecked, it leaves the whole batch licence-less. */
    @Test
    public void importItemBySafWithMissingLicenceFileAbortsBeforeAnyItemIsImported() throws Exception {
        Path missingLicence = tempDir.resolve("no-such-directory").resolve("cc-by.license");
        configurationService.setProperty(CC_LICENSE_FILE, missingLicence.toString());
        Path safDir = createSafDir(CC_BY_RIGHTS, 3);

        assertRunRefused(safDir, missingLicence);
    }

    /** An empty licence, once deposited, would satisfy the "package wins" guard for good. */
    @Test
    public void importItemBySafWithEmptyLicenceFileAbortsBeforeAnyItemIsImported() throws Exception {
        Path emptyLicence = Files.createFile(tempDir.resolve("empty-cc.license"));
        configurationService.setProperty(CC_LICENSE_FILE, emptyLicence.toString());
        Path safDir = createSafDir(CC_BY_RIGHTS, 3);

        assertRunRefused(safDir, emptyLicence);
    }

    /** A key that is present but empty does not fall back to the coded default, so no trigger is left. */
    @Test
    public void importItemBySafWithBlankRightsValueAbortsBeforeAnyItemIsImported() throws Exception {
        configurationService.setProperty(CC_LICENSE_RIGHTS_VALUE, "");
        Path safDir = createSafDir(CC_BY_RIGHTS, 3);

        String message = assertRunRefused(safDir);
        assertTrue("the failure must name the configuration key to fix, was: " + message,
                message.contains(CC_LICENSE_RIGHTS_VALUE));
    }

    /** One usable value is left, so a stray blank must not refuse the whole batch. */
    @Test
    public void importItemBySafWithBlankAmongTheConfiguredRightsValuesUsesTheRest() throws Exception {
        configurationService.setProperty(CC_LICENSE_RIGHTS_VALUE, new String[] { "", CC_BY_RIGHTS });
        Path safDir = createSafDir(CC_BY_RIGHTS);

        performImport(safDir);

        Item item = findArchivedItem();
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
    }

    /** The British spelling must work out of the box, with nothing configured. */
    @Test
    public void importItemBySafWithTheOtherShippedRightsValueCreatesCcLicenseBundle() throws Exception {
        Path safDir = createSafDir(CC_BY_RIGHTS_BRITISH);

        performImport(safDir);

        Item item = findArchivedItem();
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
        assertRightsMetadataUntouched(item, CC_BY_RIGHTS_BRITISH);
    }

    @Test
    public void importItemBySafWithConfiguredAlternativeRightsValueCreatesCcLicenseBundle() throws Exception {
        configurationService.setProperty(CC_LICENSE_RIGHTS_VALUE,
                new String[] { CC_BY_RIGHTS, CC_BY_RIGHTS_CUSTOM });
        Path safDir = createSafDir(CC_BY_RIGHTS_CUSTOM);

        performImport(safDir);

        Item item = findArchivedItem();
        Bitstream licence = assertSingleCcLicenseBitstream(item);
        assertCcLicenseBitstreamShape(licence);
    }

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

    /** @return the failure message, so the caller can check it names whatever needs fixing */
    private String assertRunRefused(Path safDir) throws Exception {
        Exception thrown = assertThrows(Exception.class, () -> performImport(safDir));

        assertTrue("no item may have been imported", findArchivedItems().isEmpty());
        assertTrue("no item may have been left in the workflow",
                workflowItemService.findByCollection(context, collection).isEmpty());
        assertFalse("the mapfile must not have been written, or --resume would skip the failed items",
                Files.exists(mapFile()));
        return String.valueOf(thrown.getMessage());
    }

    private void assertRunRefused(Path safDir, Path badLicenceFile) throws Exception {
        String message = assertRunRefused(safDir);

        assertTrue("the failure must name the offending licence file, was: " + message,
                message.contains(badLicenceFile.toString()));
        assertTrue("the failure must name the configuration key to fix, was: " + message,
                message.contains(CC_LICENSE_FILE));
    }

    /**
     * Dates only DEFAULT_ITEM_READ. DEFAULT_BITSTREAM_READ is left open on purpose: that is what gives
     * the embargo test teeth, since the licence must take the item's embargo, not the open default.
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

    /** @param dcRights the dc.rights value to write, or null to omit dc.rights entirely */
    private Path createSafDir(String dcRights) throws Exception {
        return createSafDir(dcRights, 1);
    }

    /** More than one item matters for the misconfiguration tests: the damage there is a whole batch. */
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

    private Path createSafDirWithPayload(String dcRights) throws Exception {
        Path safDir = createSafDir(dcRights);
        Path itemDir = safDir.resolve(ITEM_DIR_NAME);
        Files.writeString(itemDir.resolve("contents"), PAYLOAD_FILE_NAME);
        Files.writeString(itemDir.resolve(PAYLOAD_FILE_NAME), "the deposited research data");
        return safDir;
    }

    /** @param extraArgs any extra CLI switches, e.g. "-w", "-v" or "-x" */
    private void performImport(Path safDir, String... extraArgs) throws Exception {
        List<String> args = new ArrayList<>(List.of("import", "-a",
                "-e", admin.getEmail(),
                "-c", collection.getID().toString(),
                "-s", safDir.toString(),
                "-m", mapFile().toString()));
        args.addAll(List.of(extraArgs));
        runDSpaceScript(args.toArray(new String[0]));
    }

    /** The mapfile the importer is told to write; it creates the file, so it must not exist up front. */
    private Path mapFile() {
        return tempDir.resolve("mapfile.out");
    }

    private Path defaultLicenceFile() {
        return Path.of(configurationService.getProperty("dspace.dir"), "config", "cc-by.license");
    }

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

    private Item findArchivedItem() throws Exception {
        List<Item> items = findArchivedItems();
        assertEquals("the import must have archived exactly one item", 1, items.size());
        return items.get(0);
    }

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

    /** The bitstream shape CreativeCommonsService.setLicense() produces, i.e. what a web submission leaves. */
    private void assertCcLicenseBitstreamShape(Bitstream bitstream) throws Exception {
        assertEquals("license_text", bitstream.getName());
        assertEquals("License", bitstream.getFormat(context).getShortDescription());
        assertEquals("org.dspace.license.CreativeCommons", bitstream.getSource());
    }

    /** A dc.rights.uri must not be invented either: that field is what lets the UI strip the licence again. */
    private void assertRightsMetadataUntouched(Item item, String expected) {
        List<MetadataValue> rights = itemService.getMetadataByMetadataString(item, "dc.rights");
        assertEquals("dc.rights must survive the import unchanged", 1, rights.size());
        assertEquals("dc.rights must survive the import unchanged", expected, rights.get(0).getValue());
        assertTrue("dc.rights.uri must not be invented by the import",
                itemService.getMetadataByMetadataString(item, "dc.rights.uri").isEmpty());
    }

    /** Exactly one READ policy, for Anonymous, inherited from the owning collection's defaults. */
    private void assertAnonymousReadPolicy(String what, DSpaceObject dso) throws Exception {
        Group anonymous = groupService.findByName(context, Group.ANONYMOUS);
        List<ResourcePolicy> policies = authorizeService.getPoliciesActionFilter(context, dso, Constants.READ);
        assertEquals("the " + what + " must carry exactly one READ policy", 1, policies.size());
        assertEquals("the " + what + " must be readable by Anonymous, or the licence 403s for visitors",
                anonymous, policies.get(0).getGroup());
        assertEquals("the " + what + "'s READ policy must be inherited from the collection defaults",
                ResourcePolicy.TYPE_INHERITED, policies.get(0).getRpType());
    }

    /** As above, but the policy has not started yet: the item's embargo came down onto the licence too. */
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

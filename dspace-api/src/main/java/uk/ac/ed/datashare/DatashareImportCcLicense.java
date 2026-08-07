/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.license.factory.LicenseServiceFactory;
import org.dspace.license.service.CreativeCommonsService;
import org.dspace.scripts.handler.DSpaceRunnableHandler;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;

/**
 * Datashare Creative Commons licence helper for the SAF batch import ({@code dspace import}).
 *
 * <p>DataShare (DSpace 6) attached the Creative Commons Attribution 4.0 licence to every batch-imported
 * item whose {@code dc.rights} announced that licence. Vanilla DSpace has never done this, and the
 * classpath-shadowing class that did it was dropped in the DSpace 8 migration, so batch-imported items
 * lost their CC-LICENSE bundle while web-submitted ones kept theirs. This restores it, producing exactly
 * what the web submission produces via
 * {@code org.dspace.app.rest.submit.step.datashare.DatashareLicenseStep}: one CC-LICENSE bundle holding a
 * single {@code license_text} bitstream of format "License" and source
 * {@code org.dspace.license.CreativeCommons}.</p>
 *
 * <p>Two entry points, both called from {@code ItemImportServiceImpl}:</p>
 * <ul>
 *   <li>{@link #validateConfiguration(boolean)} once per run, before the first item is created and before
 *       the mapfile is opened;</li>
 *   <li>{@link #attachIfDeclared} per item, once the item is complete, i.e. after both the workflow and
 *       the install branch of {@code addItem()}, because production imports with {@code --workflow} and
 *       the install branch is then never reached.</li>
 * </ul>
 *
 * <p>Read policies are deliberately <em>not</em> touched. Whatever the item's state when the hook fires,
 * DSpace 8 already gives the new bundle and bitstream the right ones: {@code bundleService.create()} ends
 * in {@code ItemServiceImpl.addBundle()}, which calls
 * {@code authorizeService.inheritPolicies(context, item, bundle, true)}, and
 * {@code bitstreamService.create()} ends in {@code BundleServiceImpl.addBitstream()}, which inherits the
 * bundle's policies and then applies the owning collection's DEFAULT_BITSTREAM_READ. An earlier revision
 * re-applied the collection defaults here; it was verified to be dead code (every policy it would have
 * added is already in place, so {@code addDefaultPoliciesNotInPlace()} short-circuits on
 * {@code isAnIdenticalPolicyAlreadyInPlace}) and, worse, the one case where it was <em>not</em> dead was
 * an embargoed item, where {@code addBitstream()} deliberately skips DEFAULT_BITSTREAM_READ. The
 * integration tests still assert anonymous READ on the archived paths, so the inherited behaviour stays
 * pinned.</p>
 *
 * <p>Guards: the feature can be switched off, the trigger values and the licence file are configurable, a
 * package that ships its own CC-LICENSE bundle always wins, and {@code dc.rights} may be absent. The
 * {@code dc.rights} value itself is never touched (DSpace 6 cleared it; the DSpace 8 web submission does
 * not, and we match DSpace 8).</p>
 *
 * <p>Failure handling is split deliberately. A misconfiguration is the same for every item, so it aborts
 * the run up front rather than producing thousands of silently licence-less items whose mapfile lines
 * would make {@code --resume} skip them. A genuinely per-item failure (an I/O or authorisation problem
 * while depositing this one licence) is reported against the item and the batch carries on, after any
 * half-built CC-LICENSE bundle has been discarded so that a corrective re-import is not blocked by the
 * "package wins" guard. Anything else - an SQLException, or a runtime exception such as Hibernate's
 * PersistenceException, after which the session is by contract unusable - propagates, because swallowing
 * it would only relocate the failure thousands of items away from its cause.</p>
 */
public final class DatashareImportCcLicense {

    /** Master switch for the hook; on by default for DataShare. */
    public static final String CFG_ENABLED = "itemimport.cc-license.enabled";

    /** The {@code dc.rights} values that ask for the licence. Repeatable / comma separated. */
    public static final String CFG_RIGHTS_VALUE = "itemimport.cc-license.rights-value";

    /** The licence file deposited as the CC-LICENSE bitstream. */
    public static final String CFG_FILE = "itemimport.cc-license.file";

    /**
     * Trigger used when {@link #CFG_RIGHTS_VALUE} is not configured: the value DatashareLicenseStep
     * matches on the web submission path.
     */
    static final String DEFAULT_RIGHTS_VALUE = "Creative Commons Attribution 4.0 International Public License";

    /** The field DataShare announces the licence in, on both the web and the batch path. */
    private static final String RIGHTS_FIELD = "dc.rights";

    /** Mime type handed to setLicense(); it is what selects the "License" bitstream format. */
    private static final String LICENCE_MIME_TYPE = "text/plain";

    private static final Logger log = LogManager.getLogger(DatashareImportCcLicense.class);

    private DatashareImportCcLicense() {
    }

    /**
     * Pre-flight check, to be run once per import run before the first item is created and before the
     * mapfile is opened. Aborts the run when the feature is on but the configured licence file cannot be
     * used, naming both the path and the configuration key.
     *
     * <p>This is the treatment for a <em>misconfiguration</em>, which by definition affects every item in
     * the run. Reporting it per item instead would let the run finish "successfully" with N licence-less
     * items and N mapfile lines, after which {@code --resume} skips exactly those items and recovery needs
     * a hand-written script.</p>
     *
     * <p>The file is validated by reading it, not by {@code File.canRead()}: {@code canRead()} is
     * unreliable against NTFS ACLs and says nothing about the content, and a 0-byte licence (an
     * interrupted copy, a template that rendered to nothing) would otherwise be deposited silently and
     * then be protected forever by the "package wins" guard on any re-import.</p>
     *
     * @param hookWillNotRun true when this run will never reach the hook, so the configuration is
     *                       irrelevant to it and refusing the run would be wrong. That covers {@code -x} /
     *                       {@code --exclude-bitstreams} (no bitstreams are created at all) and {@code -v} /
     *                       {@code --validate} (a dry run that imports nothing - note the customer's own
     *                       batch_import.sh maps its "-t" test switch onto {@code -v}, so refusing it would
     *                       break their documented way of rehearsing an import)
     * @throws IOException if the feature is enabled and the licence file is missing, unreadable or empty,
     *                     or no usable trigger value is configured
     */
    public static void validateConfiguration(boolean hookWillNotRun) throws IOException {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        if (hookWillNotRun || !configurationService.getBooleanProperty(CFG_ENABLED, true)) {
            return;
        }
        if (configuredTriggers(configurationService).isEmpty()) {
            throw new IOException("Datashare: the Creative Commons licence is enabled for this import but "
                    + CFG_RIGHTS_VALUE + " has no usable value, so no imported item could ever match and every "
                    + "item would silently end up without a licence. Give it the dc.rights value to match on, "
                    + "or set " + CFG_ENABLED + " = false to import without a licence.");
        }
        File licenceFile = licenceFile(configurationService);
        int length;
        try (InputStream licence = new FileInputStream(licenceFile)) {
            length = licence.readAllBytes().length;
        } catch (IOException e) {
            throw new IOException(unusableLicence(licenceFile) + ": " + e, e);
        }
        if (length == 0) {
            throw new IOException(unusableLicence(licenceFile) + ": the file is empty");
        }
        log.debug("Datashare: Creative Commons licence file {} validated, {} bytes", licenceFile, length);
    }

    /**
     * Attach the configured Creative Commons licence to a freshly imported item, if its {@code dc.rights}
     * asks for it. No-op when the feature is off, when {@code dc.rights} is absent or does not match, or
     * when the SAF package already brought its own CC-LICENSE bundle.
     *
     * @param context the current DSpace Context (the importer runs with authorisation turned off)
     * @param item    the imported item, in workflow or already archived
     * @param handler the script handler to report through, or null to fall back to the log
     * @throws SQLException       on a database error, which compromises the session and must abort the run
     * @throws IOException        if a half-built CC-LICENSE bundle could not be discarded again
     * @throws AuthorizeException if a half-built CC-LICENSE bundle could not be discarded again
     */
    public static void attachIfDeclared(Context context, Item item, DSpaceRunnableHandler handler)
            throws SQLException, IOException, AuthorizeException {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        if (item == null || !configurationService.getBooleanProperty(CFG_ENABLED, true)) {
            return;
        }
        ItemService itemService = ContentServiceFactory.getInstance().getItemService();
        if (!declaresConfiguredLicence(item, itemService, configurationService)) {
            return;
        }
        if (!itemService.getBundles(item, CreativeCommonsService.CC_BUNDLE_NAME).isEmpty()) {
            // The package declared "bundle:CC-LICENSE" in its contents file. The package always wins:
            // CreativeCommonsService.setLicense() would delete and recreate the bundle, losing it.
            log.info("Datashare: item {} was imported with its own {} bundle, leaving it alone",
                    item.getID(), CreativeCommonsService.CC_BUNDLE_NAME);
            return;
        }

        File licenceFile = licenceFile(configurationService);
        try (InputStream licence = new FileInputStream(licenceFile)) {
            LicenseServiceFactory.getInstance().getCreativeCommonsService()
                    .setLicense(context, item, licence, LICENCE_MIME_TYPE);
        } catch (IOException | AuthorizeException e) {
            // Per-item, and the session is still sound: clean up and let the batch carry on. Only an I/O
            // or authorisation problem lands here; SQLExceptions and runtime exceptions (Hibernate's
            // PersistenceException among them) are left to propagate and abort the run, because after
            // those the session is unusable and every later item would fail for the wrong reason.
            discardHalfBuiltCcBundle(context, item, itemService);
            report(handler, "Datashare: could not attach the Creative Commons licence from '" + licenceFile
                    + "' (" + CFG_FILE + ") to imported item " + item.getID(), e);
            return;
        }
        log.info("Datashare: attached {} from {} to imported item {}",
                CreativeCommonsService.CC_BUNDLE_NAME, licenceFile, item.getID());
    }

    /**
     * Whether the item's {@code dc.rights} announces one of the configured licences. Null and empty safe:
     * an item with no {@code dc.rights} simply gets no licence (the DSpace 6 code threw here). Any value
     * of the field counts, not only the first, so a record that lists the licence second is not missed.
     *
     * <p>Known asymmetry with the web submission, recorded rather than fixed: DatashareLicenseStep looks
     * at {@code dc.rights} index 0 only, and when that one value does not match it calls
     * {@code CreativeCommonsService.removeLicense()}. So on a multi-valued {@code dc.rights} whose licence
     * value is not first, a later curator edit in the UI can undo what was done here - and because
     * {@code cc.license.name = dc.rights}, that removal clears the {@code dc.rights} value too. Today the
     * removal is gated on {@code dc.rights.uri} being present, which neither this hook nor
     * DatashareLicenseStep ever sets, so the reversal is latent rather than active. Narrowing this match
     * to index 0 would instead make the batch path miss records the customer expects to be covered, so
     * the mismatch is left visible here; fixing it properly means changing DatashareLicenseStep.</p>
     */
    private static boolean declaresConfiguredLicence(Item item, ItemService itemService,
            ConfigurationService configurationService) {
        List<MetadataValue> rights = itemService.getMetadataByMetadataString(item, RIGHTS_FIELD);
        if (rights == null || rights.isEmpty()) {
            return false;
        }
        List<String> triggers = configuredTriggers(configurationService);
        for (MetadataValue value : rights) {
            if (value != null && triggers.contains(value.getValue())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code dc.rights} values that ask for the licence, blanks dropped.
     *
     * <p>The built-in default only applies when the key is absent altogether. ConfigurationService counts a
     * key that is present but empty as configured, and returns an <em>empty array</em> for it, so
     * {@code itemimport.cc-license.rights-value =} in a local.cfg would leave nothing to match on and
     * quietly turn out a whole run of licence-less items. {@link #validateConfiguration(boolean)} refuses
     * such a run rather than let that happen.</p>
     *
     * <p>Dropping blanks is defence in depth for the multi-valued case, where a stray empty entry does
     * survive into the array. It cannot be provoked through an import - {@code ItemImportServiceImpl}
     * discards empty metadata values as it reads dublin_core.xml, so no item ever carries a blank
     * {@code dc.rights} for a blank trigger to match - and it is therefore deliberately not pinned by a
     * test rather than pinned by one that would pass either way.</p>
     */
    private static List<String> configuredTriggers(ConfigurationService configurationService) {
        return Arrays.stream(configurationService.getArrayProperty(CFG_RIGHTS_VALUE,
                        new String[] { DEFAULT_RIGHTS_VALUE }))
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    /**
     * Drop an empty CC-LICENSE bundle left behind by a failed {@code setLicense()}.
     *
     * <p>{@code setLicense()} creates the bundle first and only then the bitstream, so a failure in
     * between leaves an empty bundle queued for commit. That bundle would satisfy the "package wins"
     * guard above forever, so a corrective re-import would politely refuse to repair the item. Only ever
     * removes a CC-LICENSE bundle that holds no bitstreams, i.e. never a real licence.</p>
     */
    private static void discardHalfBuiltCcBundle(Context context, Item item, ItemService itemService)
            throws SQLException, IOException, AuthorizeException {
        for (Bundle bundle : itemService.getBundles(item, CreativeCommonsService.CC_BUNDLE_NAME)) {
            if (bundle.getBitstreams().isEmpty()) {
                itemService.removeBundle(context, item, bundle);
            }
        }
    }

    /**
     * The licence file to deposit, defaulting to the very file the web submission deposits.
     */
    private static File licenceFile(ConfigurationService configurationService) {
        return new File(configurationService.getProperty(CFG_FILE,
                configurationService.getProperty("dspace.dir") + File.separator + "config"
                        + File.separator + "cc-by.license"));
    }

    /**
     * The operator-facing description of an unusable licence file: what is wrong, which key points at it,
     * and how to import without it.
     */
    private static String unusableLicence(File licenceFile) {
        return "Datashare: the Creative Commons licence file '" + licenceFile + "' (" + CFG_FILE
                + ") cannot be used, so no imported item would get a " + CreativeCommonsService.CC_BUNDLE_NAME
                + " bundle. Fix the file, or set " + CFG_ENABLED + " = false to import without it";
    }

    /**
     * Report a per-item problem to the operator running the import, mirroring ItemImportServiceImpl's own
     * logging: the script handler when there is one, the log otherwise.
     */
    private static void report(DSpaceRunnableHandler handler, String message, Exception e) {
        if (handler != null) {
            handler.logError(message, e);
        } else {
            log.error(message, e);
        }
    }
}

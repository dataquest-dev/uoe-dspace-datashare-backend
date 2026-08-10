/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
 * Attaches a Creative Commons licence to SAF-imported items whose {@code dc.rights} asks for one,
 * producing the same CC-LICENSE bundle the web submission produces. Called from
 * {@code ItemImportServiceImpl}: {@link #validateConfiguration(boolean)} once per run,
 * {@link #attachIfDeclared} per item.
 */
public final class DatashareImportCcLicense {

    public static final String CFG_ENABLED = "itemimport.cc-license.enabled";

    public static final String CFG_RIGHTS_VALUE = "itemimport.cc-license.rights-value";

    public static final String CFG_FILE = "itemimport.cc-license.file";

    /**
     * The first is what the web submission step matches on; the second is the spelling real SAF packages
     * carry. Both are live by default, because an item is either CC-BY or it is not and the wording it
     * was typed in should not decide that.
     */
    static final String[] DEFAULT_RIGHTS_VALUES = {
        "Creative Commons Attribution 4.0 International Public License",
        "Creative Commons Attribution 4.0 International licence",
    };

    private static final String RIGHTS_FIELD = "dc.rights";

    /** Selects the "License" bitstream format in setLicense(). */
    private static final String LICENCE_MIME_TYPE = "text/plain";

    private static final Logger log = LogManager.getLogger(DatashareImportCcLicense.class);

    private DatashareImportCcLicense() {
    }

    /**
     * Refuses the run when the licence cannot be attached to any item. Call before the mapfile is
     * opened, so a refused run leaves nothing for {@code --resume} to skip.
     *
     * @param hookWillNotRun true for runs that never attach a licence ({@code -x}, {@code -v})
     * @throws IOException if no trigger value is configured, or the licence file cannot be read
     */
    public static void validateConfiguration(boolean hookWillNotRun) throws IOException {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        if (hookWillNotRun || !configurationService.getBooleanProperty(CFG_ENABLED, true)) {
            return;
        }
        if (configuredTriggers(configurationService).isEmpty()) {
            throw new IOException("The Creative Commons licence is enabled for this import but "
                    + CFG_RIGHTS_VALUE + " has no usable value, so nothing could ever match it and every item "
                    + "would end up without a licence. Set the dc.rights value to match on, or set "
                    + CFG_ENABLED + " = false to import without a licence.");
        }
        File licenceFile = licenceFile(configurationService);
        int length = readLicence(licenceFile).length;
        if (length == 0) {
            throw new IOException(unusableLicence(licenceFile) + ": the file is empty");
        }
        log.debug("Creative Commons licence file {} validated, {} bytes", licenceFile, length);
    }

    /**
     * Attaches the configured licence to a freshly imported item. No-op when the feature is off, when
     * {@code dc.rights} does not match, or when the package brought its own CC-LICENSE bundle.
     *
     * @param handler script handler to report per-item problems through, or null to use the log
     * @throws IOException        if the licence file became unusable mid-run, so every item left is lost
     * @throws SQLException       on a database error, after which the session cannot be trusted
     * @throws AuthorizeException if a half-built bundle could not be discarded
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
            // setLicense() would delete and recreate the bundle, so leave the package's own one alone.
            log.info("Item {} was imported with its own {} bundle, leaving it alone",
                    item.getID(), CreativeCommonsService.CC_BUNDLE_NAME);
            return;
        }

        // Read outside the catch below: a licence file that has become unusable is not an item-specific
        // problem, and continuing would silently produce licence-less items for the rest of the run.
        File licenceFile = licenceFile(configurationService);
        byte[] licence = readLicence(licenceFile);

        try (InputStream in = new ByteArrayInputStream(licence)) {
            LicenseServiceFactory.getInstance().getCreativeCommonsService()
                    .setLicense(context, item, in, LICENCE_MIME_TYPE);
        } catch (IOException | AuthorizeException e) {
            // Item-specific and the session is still sound, so carry on. SQLExceptions and runtime
            // exceptions propagate instead: after those the session is unusable.
            discardHalfBuiltCcBundle(context, item, itemService);
            report(handler, "Could not attach the Creative Commons licence from '" + licenceFile
                    + "' (" + CFG_FILE + ") to imported item " + item.getID(), e);
            return;
        }
        log.info("Attached {} from {} to imported item {}",
                CreativeCommonsService.CC_BUNDLE_NAME, licenceFile, item.getID());
    }

    /**
     * Whether any {@code dc.rights} value matches a configured trigger. Absent metadata simply means no
     * licence.
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
     * Configured trigger values, blanks dropped. A key that is present but empty yields an empty list,
     * not the default, which {@link #validateConfiguration(boolean)} refuses.
     */
    private static List<String> configuredTriggers(ConfigurationService configurationService) {
        return Arrays.stream(configurationService.getArrayProperty(CFG_RIGHTS_VALUE, DEFAULT_RIGHTS_VALUES))
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    private static byte[] readLicence(File licenceFile) throws IOException {
        try {
            return Files.readAllBytes(licenceFile.toPath());
        } catch (IOException e) {
            throw new IOException(unusableLicence(licenceFile) + ": " + e, e);
        }
    }

    /**
     * Removes an empty CC-LICENSE bundle left by a failed setLicense(), which would otherwise make a
     * corrective re-import skip the item. Never touches a bundle that holds a bitstream.
     */
    private static void discardHalfBuiltCcBundle(Context context, Item item, ItemService itemService)
            throws SQLException, IOException, AuthorizeException {
        for (Bundle bundle : itemService.getBundles(item, CreativeCommonsService.CC_BUNDLE_NAME)) {
            if (bundle.getBitstreams().isEmpty()) {
                itemService.removeBundle(context, item, bundle);
            }
        }
    }

    private static File licenceFile(ConfigurationService configurationService) {
        return new File(configurationService.getProperty(CFG_FILE,
                configurationService.getProperty("dspace.dir") + File.separator + "config"
                        + File.separator + "cc-by.license"));
    }

    private static String unusableLicence(File licenceFile) {
        return "The Creative Commons licence file '" + licenceFile + "' (" + CFG_FILE
                + ") cannot be used, so no imported item would get a " + CreativeCommonsService.CC_BUNDLE_NAME
                + " bundle. Fix the file, or set " + CFG_ENABLED + " = false to import without it";
    }

    private static void report(DSpaceRunnableHandler handler, String message, Exception e) {
        if (handler != null) {
            handler.logError(message, e);
        } else {
            log.error(message, e);
        }
    }
}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.storage.bitstore.factory.StorageServiceFactory;
import org.dspace.storage.bitstore.service.BitstreamStorageService;

/**
 * DataShare item dataset. That is a zip file that contains all item bitstreams.
 */
public class DatashareItemDataset {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareItemDataset.class);

    // Bundle name constants
    private static final String ORIGINAL_BUNDLE = "ORIGINAL";
    private static final String CC_LICENSE_BUNDLE = "CC-LICENSE";
    private static final String LICENSE_BUNDLE = "LICENSE";

    // File and directory constants
    private static final String TMP_FILE_NAME_EXT = ".tmp";
    private static final String DIR_PROP = "datasets.path";

    // Metadata constants
    private static final String DC_DATE_EMBARGO = "dc.date.embargo";

    // Static variables
    private static String dir = null;

    // Instance variables
    private Context context = null;
    private Item item = null;
    private String handle = null;

    /**
     * Initialise dataset with DSpace context and item.
     *
     * @param context
     * @param item
     */
    public DatashareItemDataset(Context context, Item item) {
        this.context = context;
        this.item = item;
        this.init();
    }

    /**
     * Initialise dataset with DSpace context and item handle.
     *
     * @param context
     * @param handle
     */
    public DatashareItemDataset(Context context, String handle) {
        this.context = context;
        this.handle = handle;
        this.init();
    }

    /**
     * Initialise dataset with DSpace context and dataset zip file.
     *
     * @param context
     * @param ds
     */
    public DatashareItemDataset(Context context, File ds) {
        this.context = context;
        this.setHandle(ds);
        this.init();
    }

    public DatashareItemDataset(Item item) {
        this.item = item;
        this.init();
    }

    public DatashareItemDataset(String handle) {
        this.handle = handle;
        this.init();
    }

    public DatashareItemDataset(Context context, Bitstream bitstream) {
        try {
            BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
            DSpaceObject ob = bitstreamService.getParentObject(context, bitstream);
            if (ob instanceof Item) {
                this.context = context;
                this.item = (Item) ob;
                this.init();
            } else {
                throw new RuntimeException("Only items can be datasets.");
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    // 2. INITIALIZATION METHODS

    /**
     * Initialise dataset.
     */
    private void init() {
        dir = DSpaceServicesFactory.getInstance().getConfigurationService().getProperty(DIR_PROP);
        log.info("init()- dir: " + dir);
        if (dir == null) {
            throw new RuntimeException(DIR_PROP + " needs to be defined");
        }

        if (!new File(dir).exists()) {
            throw new RuntimeException(dir + " doesn't exist");
        }

    }

    // 3. PUBLIC INSTANCE METHODS (alphabetically)

    /**
     * Check if item has been put under embargo. If so, delete dataset.
     */
    public void checkDataset() {
        if (this.exists()) {
            if (hasEmbargo(this.context, this.item)) {
                log.info("Delete dataset for " + item.getHandle());
                this.delete();
            }
        } else {
            log.warn("No dataset exists to check " + this.item.getHandle());
        }
    }

    /**
     * Create dataset zip file.
     *
     * @return Thread that dataset is created on.
     */
    public Thread createDataset() {
        Thread th = new Thread(new DatasetZip());
        th.start();
        return th;
    }

    /**
     * Synchronously create the dataset zip and register its database record using the supplied
     * context (no new thread, no separate context). Used by the event consumer when a new item is
     * archived so the zip is available immediately, mirroring DataShare 6.x. The batch
     * {@link #createDataset()} path runs exactly the same logic on its own thread/context.
     *
     * @param context DSpace context used to read bitstreams and persist the dataset record.
     */
    public void createDatasetSync(Context context) {
        new DatasetZip().generate(context);
    }

    /**
     * Delete dataset from system.
     */
    public void delete() {
        File zip = null;
        if (this.item != null) {
            zip = new File(this.getFullPath());
        } else {
            if (this.handle != null) {
                zip = new File(dir + File.separator + DatashareItemDataset.getFileName(this.handle));
            }
        }

        if (zip == null) {
            log.warn("No dataset file to delete for item or handle.");
            return;
        }

        if (!zip.delete()) {
            log.warn("Problem deleting " + zip);
        } else {
            String fp = zip.toString();
            String fname = fp.substring(fp.lastIndexOf('/') + 1);
            DatashareDatasetService datashareDatasetService = ContentServiceFactory.getInstance()
                    .getDatashareDatasetService();
            try {
                datashareDatasetService.deleteDatashareDataset(context, fname);
            } catch (Exception e) {
                log.warn("Problem deleting " + fname);
            }
        }
    }

    public boolean exists() {
        log.info("getFullPath(): " + getFullPath());

        return new File(getFullPath()).exists();
    }

    public String getChecksum() throws SQLException {
        DatashareDatasetService datashareDatasetService = ContentServiceFactory.getInstance()
                .getDatashareDatasetService();
        return datashareDatasetService.fetchDatashareDatasetChecksum(context, item);
    }

    private String getFileName() {
        return DatashareItemDataset.getFileName(this.item.getHandle());
    }

    public String getFullPath() {
        return dir + File.separator + getFileName();
    }

    /**
     * @return size in bytes of dataset zip file.
     */
    public long getSize() {
        return new File(getFullPath()).length();
    }

    /**
     * @return Temporary dataset file name.
     */
    public String getTmpFileName() {
        return getFullPath() + TMP_FILE_NAME_EXT;
    }

    /**
     * @return size in bytes of dataset tmp zip file.
     */
    public long getTmpSize() {
        return new File(getTmpFileName()).length();
    }

    // 4. PRIVATE INSTANCE METHODS (alphabetically)

    private String getHandle() {
        return this.handle;
    }

    /**
     * Create a monitor on dataset creation, to track progress.
     *
     * @return Thread that monitor is created on.
     */
    public Thread monitorDataset() {
        Thread th = new Thread(new DatasetMonitor());
        th.start();
        return th;
    }

    /**
     * Given a dataset file object, set handle.
     */
    private void setHandle(File ds) {
        Pattern p = Pattern.compile(".*DS_(\\d+)_(\\d+)\\.zip");
        Matcher matcher = p.matcher(ds.getAbsolutePath());
        while (matcher.find()) {
            this.handle = matcher.group(1) + "/" + matcher.group(2);
        }
    }

    // 5. STATIC METHODS
    public static boolean exists(String handle) {
        return new File(dir + getFileName(handle)).exists();
    }

    public static String getFileName(String handle) {
        String aHandle[] = handle.split("/");
        return "DS_" + aHandle[0] + "_" + aHandle[1] + ".zip";
    }

    public static String getFullFilePath(String handle) {
        String dir = DSpaceServicesFactory.getInstance().getConfigurationService().getProperty(DIR_PROP);
        return dir + File.separator + getFileName(handle);
    }

    /**
     * Determine whether all of an item's bitstreams may be exposed in a dataset zip. Because the
     * generated zip is served as a static file with no per-request authorization, it may only be
     * exposed when the item is publicly available: it must be archived, not under embargo, not
     * withdrawn, and the whole access path packaged into the zip (the item, each zip bundle and each
     * bitstream) must be readable by the Anonymous user. Otherwise a guessed zip URL would leak
     * restricted content. This is the single existence rule for the zip; all call sites (generation,
     * lookup and the event consumer) rely on it.
     *
     * @param context DSpace context.
     * @param item    DSpace item.
     * @return true if the item's bitstreams can be made available.
     */
    public static boolean areAllItemBitstreamsAvailable(Context context, Item item) {
        log.info("isArchived: " + item.isArchived());
        log.info("hasEmbargo: " + hasEmbargo(context, item));
        log.info("isWithdrawn: " + item.isWithdrawn());
        return item.isArchived()
                && !hasEmbargo(context, item)
                && !item.isWithdrawn()
                && isZipContentAnonymouslyReadable(context, item);
    }

    /**
     * Whether the whole access path packaged into the dataset zip is readable by the Anonymous user:
     * the item itself, each of the zip bundles (ORIGINAL, CC-LICENSE, LICENSE) and every bitstream
     * within them. A restrictive policy at <em>any</em> level (item, bundle or bitstream) makes the
     * content non-public, so the static zip must not exist. The check is evaluated as the Anonymous
     * user (eperson == null) against a context with authorization enabled and no special groups, so
     * it is unaffected by an "ignore authorization" context or by IP-based special groups (either of
     * which would otherwise report access as allowed for content that is not truly public).
     *
     * @param context DSpace context (used directly only when it enforces authorization and carries
     *                no special groups).
     * @param item    DSpace item.
     * @return true if the item, its zip bundles and their bitstreams are all anonymously readable.
     */
    public static boolean isZipContentAnonymouslyReadable(Context context, Item item) {
        AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
        ItemService itemService = ContentServiceFactory.getInstance().getItemService();
        GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();
        Context evalContext = context;
        Context tempContext = null;
        try {
            if (context == null) {
                // No caller context (batch path): a fresh context is safe here - there is no shared
                // session/transaction to disturb.
                tempContext = new Context(Context.Mode.READ_ONLY);
                evalContext = tempContext;
                item = itemService.find(evalContext, item.getID());
                if (item == null) {
                    return false;
                }
            }
            // Decide readability from the objects' own READ resource policies, NOT via the caller
            // context's authorization state. This keeps the result correct regardless of the caller's
            // special groups (e.g. DATASHARE_USERS / IP-based) or an "ignore authorization" context -
            // either of which would otherwise report restricted content as readable - while NOT
            // creating a second Context. DSpace binds one Hibernate session per thread
            // (HibernateDBConnection#getSession -> sessionFactory.getCurrentSession()), so a second
            // Context.abort() would close the session shared with the caller's transaction (e.g. the
            // in-progress archival), detach its entities and roll the whole operation back
            // (LazyInitializationException during zip generation).
            Group anonymous = groupService.findByName(evalContext, Group.ANONYMOUS);
            if (anonymous == null) {
                return false;
            }
            // The item must be anonymously readable...
            if (!isReadableByAnonymous(authorizeService, groupService, evalContext, item, anonymous)) {
                return false;
            }
            String[] zipBundles = { ORIGINAL_BUNDLE, CC_LICENSE_BUNDLE, LICENSE_BUNDLE };
            for (String bundleName : zipBundles) {
                for (Bundle bundle : itemService.getBundles(item, bundleName)) {
                    // ...as must each bundle that goes into the zip (a restricted bundle hides its
                    // files), even though DSpace itself only gates direct bitstream download on the
                    // bitstream policy...
                    if (!isReadableByAnonymous(authorizeService, groupService, evalContext, bundle, anonymous)) {
                        return false;
                    }
                    for (Bitstream bitstream : bundle.getBitstreams()) {
                        // ...and so must every bitstream.
                        if (!isReadableByAnonymous(authorizeService, groupService, evalContext, bitstream,
                                anonymous)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (SQLException e) {
            log.error("Error checking anonymous readability of zip content for item "
                    + (item != null ? item.getID() : null), e);
            return false;
        } finally {
            if (tempContext != null) {
                try {
                    tempContext.abort();
                } catch (Exception e) {
                    // ignore - read-only context
                }
            }
        }
    }

    /**
     * Whether the given object is readable by the Anonymous user, evaluated from the object's own
     * currently-valid READ resource policies. An object is anonymously readable if any group holding a
     * valid READ policy is the Anonymous group itself or has Anonymous as a (transitive) subgroup -
     * mirroring {@link org.dspace.eperson.service.GroupService#isMember}, which DSpace authorization
     * uses to grant Anonymous access through such parent groups. This is independent of the caller
     * context's special groups or ignore-authorization state and creates no second Context.
     *
     * @param authorizeService authorize service
     * @param groupService     group service
     * @param context          DSpace context
     * @param dso              the object (item, bundle or bitstream) to check
     * @param anonymous        the Anonymous group
     * @return true if {@code dso} is readable by Anonymous
     * @throws SQLException if a database error occurs
     */
    private static boolean isReadableByAnonymous(AuthorizeService authorizeService, GroupService groupService,
            Context context, DSpaceObject dso, Group anonymous) throws SQLException {
        for (Group group : authorizeService.getAuthorizedGroups(context, dso, Constants.READ)) {
            if (anonymous.equals(group) || groupService.isParentOf(context, group, anonymous)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does the item have an embargo?
     *
     * @param context DSpace context.
     * @param item    DSpace item.
     * @return True if the dspace item is embargoed.
     */
    public static boolean hasEmbargo(Context context, Item item) {
        boolean hasEmbargo = true;
        ItemService itemService = ContentServiceFactory.getInstance().getItemService();

        // List<MetadataValue> embargoList = itemService.getMetadataByMetadataString(item,
        //         configurationService.getProperty("embargo.field.lift"));
        List<MetadataValue> embargoList = itemService.getMetadataByMetadataString(item, DC_DATE_EMBARGO);
        if (embargoList == null || embargoList.size() == 0) {
            hasEmbargo = false;
        } else {
            // Check if embargo date is in the past
            try {
                String embargoDateStr = embargoList.get(0).getValue();
                log.info("embargoDateStr: " + embargoDateStr);

                Date embargoDate = parseDate(embargoDateStr);
                Date now = new Date();
                log.info("embargoDate: " + embargoDate + ", now: " + now);
                if (embargoDate != null && embargoDate.before(now)) {
                    hasEmbargo = false;
                }
            } catch (Exception ex) {
                log.error("Problem parsing embargo date: " + ex.getMessage());
            }
        }

        log.info(item.getID() + " hasEmbargo: " + hasEmbargo);

        return hasEmbargo;
    }

    public static String getURL(Item item) {
        String url = null;
        try {
            String baseDownloadUrl = DSpaceServicesFactory.getInstance().getConfigurationService()
                    .getProperty("datashare.download.zip.url");
            String filePath = "/" + getFileName(item.getHandle());
            url = baseDownloadUrl + filePath;

        } catch (Exception ex) {
            log.error(ex.getMessage());
        }

        return url;
    }

    /**
     * Parse a date string in ISO format yyyy-MM-dd and return a Date
     * representing the start of that day in the system default timezone.
     * Returns null when the input is null, empty or not in the expected format.
     *
     * @param dateStr date string expected in yyyy-MM-dd
     * @return parsed Date or null
     */
    public static Date parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        try {
            // Use Java 8 date time API to parse date
            java.time.LocalDate ld = java.time.LocalDate.parse(dateStr,
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            java.time.ZonedDateTime zdt = ld.atStartOfDay(java.time.ZoneId.systemDefault());
            return Date.from(zdt.toInstant());
        } catch (java.time.format.DateTimeParseException ex) {
            log.error("Problem parsing date: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Create dataset zip file in a seperate thread.
     */
    private class DatasetZip implements Runnable {
        /**
         * Start thread.
         */
        public void run() {
            Context context = null;
            try {
                context = new Context();
                generate(context);
            } catch (Exception ex) {
                log.error("Failed to create DatashareDataset: ", ex);
                // throw new RuntimeException(ex);
            } finally {
                try {
                    if (context != null) {
                        context.complete();
                    }
                } catch (SQLException ex) {
                    log.warn(ex);
                }
            }
        }

        /**
         * Generate the zip and register the dataset record using the supplied context. Shared by
         * the threaded {@link #run()} (batch) path and the synchronous
         * {@link DatashareItemDataset#createDatasetSync(Context)} (event consumer) path.
         *
         * @param context DSpace context used to read bitstreams and persist the dataset record.
         */
        private void generate(Context context) {
            try {
                if (areAllItemBitstreamsAvailable(context, item)) {
                    log.info("create zip for " + item.getHandle());
                    createZip(context);
                    String cksum = createChecksum(context);

                    log.info("zip complete");
                    DatashareDatasetService datashareDatasetService = ContentServiceFactory.getInstance()
                            .getDatashareDatasetService();
                    datashareDatasetService.insertDatashareDataset(context, item, getFileName(), cksum);
                } else {
                    DatashareItemDataset.log.warn("Zip creation for " + item.getHandle() + " not allowed.");
                }
            } catch (Exception ex) {
                log.error("Failed to create DatashareDataset: ", ex);
            }
        }

        private String createChecksum(Context context) {
            String cksum = null;
            try {
                FileInputStream fis = new FileInputStream(new File(getFullPath()));
                cksum = DigestUtils.md5Hex(fis);
                fis.close();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

            return cksum;
        }

        private void createZip(Context context) {
            String tmpZip = getTmpFileName();
            FileOutputStream fos = null;
            ZipOutputStream zos = null;
            try {
                final byte[] BUFFER = new byte[8192];

                fos = new FileOutputStream(tmpZip);
                zos = new ZipOutputStream(fos);
                zos.setLevel(0);

                ItemService itemService = ContentServiceFactory.getInstance().getItemService();
                BitstreamStorageService bitstreamStorageService = StorageServiceFactory.getInstance()
                        .getBitstreamStorageService();

                // Add files in items named bundles "ORIGINAL", "CC-LICENSE", and "LICENSE" to
                // zip.
                addFilesInItemsNamedBundleToZipOutputStream(ORIGINAL_BUNDLE, context, zos, BUFFER, itemService,
                        bitstreamStorageService);
                addFilesInItemsNamedBundleToZipOutputStream(CC_LICENSE_BUNDLE, context, zos, BUFFER, itemService,
                        bitstreamStorageService);
                addFilesInItemsNamedBundleToZipOutputStream(LICENSE_BUNDLE, context, zos, BUFFER, itemService,
                        bitstreamStorageService);

                zos.close();
                fos.close();

                // Rename zip from temporary file to final name
                if (!new File(tmpZip).renameTo(new File(getFullPath()))) {
                    log.error("Problem renaming " + tmpZip + " to " + getFullPath());
                }
                log.info(getFileName() + " complete");
            } catch (SQLException ex) {
                log.error(ex);
                throw new RuntimeException(ex);
            } catch (FileNotFoundException ex) {
                log.error(ex);
                throw new RuntimeException(ex);
            } catch (IOException ex) {
                final String msg = "Problem with " + tmpZip + ": " + ex.getMessage();
                log.info(msg);
                log.error(msg);
                throw new RuntimeException(msg);
            } catch (Exception ex) {
                log.error(ex);
                throw new RuntimeException(ex);
            } finally {
                // Close open streams
                try {
                    fos.close();
                } catch (Exception e) {
                    // ignored
                }
                try {
                    zos.close();
                } catch (Exception e) {
                    // ignored
                }
                // Delete temporary file on exit
                try {
                    new File(tmpZip).delete();
                } catch (Exception e) {
                    // ignored
                }
            }
        }

        /**
         * Add files in items named bundle to zip output stream.
         *
         * @param bundleName
         * @param context
         * @param zos
         * @param BUFFER
         * @param itemService
         * @param bitstreamStorageService
         * @throws SQLException
         * @throws IOException
         */
        private void addFilesInItemsNamedBundleToZipOutputStream(String bundleName, Context context,
                ZipOutputStream zos, final byte[] BUFFER, ItemService itemService,
                BitstreamStorageService bitstreamStorageService) throws SQLException, IOException {
            List<Bundle> bundle = itemService.getBundles(item, bundleName);

            log.info(bundleName + " bundle.size(): " + bundle.size());
            // Get bitstreams in bundle
            for (int i = 0; i < bundle.size(); i++) {
                // now get the actual bitstreams
                List<Bitstream> bitstreams = bundle.get(i).getBitstreams();

                for (int j = 0; j < bitstreams.size(); j++) {
                    log.info("do " + bitstreams.get(j).getName());
                    ZipEntry entry = new ZipEntry(bitstreams.get(j).getName());
                    log.info("ZipEntry entry " + entry);
                    zos.putNextEntry(entry);
                    InputStream in = bitstreamStorageService.retrieve(context, bitstreams.get(j));
                    log.info("InputStream in " + in);
                    int length = -1;
                    while ((length = in.read(BUFFER)) > -1) {
                        zos.write(BUFFER, 0, length);
                    }

                    zos.closeEntry();
                    in.close();
                }
            }
        }
    }

    /**
     * This will monitor the progress of a creation of a dataset printing out its
     * size.
     */
    private class DatasetMonitor implements Runnable {
        public void run() {
            boolean cont = true;
            int sleep = 5000;
            log.info("Checking dataset " + item.getHandle() + " ...");
            while (cont) {
                if (exists()) {
                    log.info("dataset exists");
                    cont = false;
                } else if (!areAllItemBitstreamsAvailable(context, item)) {
                    log.info("dataset creation not allowed");
                    cont = false;
                } else {
                    try {
                        Thread.sleep(sleep);
                        log.info("size: " + getTmpSize());
                    } catch (InterruptedException ex) {
                        log.info(ex);
                    }
                }
            }
        }
    }

    /**
     * Process all datasets in the system.
     */
    public static void main(String[] args) {
        Context context = null;
        try {
            log.info("*** Before context: ");
            context = new Context();
            log.info("*** context: " + context);

            ItemService itemService = ContentServiceFactory.getInstance().getItemService();
            log.info("*** itemService: " + itemService);

            List<String> itemHandles = new ArrayList<String>(10000);
            Iterator<Item> iter = itemService.findAll(context);
            log.info("*** iter: " + iter);

            while (iter.hasNext()) {
                Item item = iter.next();
                log.info("*** item: " + item);
                if (item.isArchived()) {
                    String handle = item.getHandle();
                    log.info("*** handle: " + handle);

                    if (handle == null) {
                        log.info("*** Item with id " + item.getID() + " has no handle");
                        continue;
                    }
                    itemHandles.add(item.getHandle());
                    DatashareItemDataset ds = new DatashareItemDataset(context, item);
                    if (ds.exists()) {
                        log.info("Dataset already exists " + item.getHandle());
                    } else {
                        if (areAllItemBitstreamsAvailable(context, item)) {
                            log.info("Create dataset for " + ds.getFullPath() + " for " + item.getHandle()
                                    + ", id: " + item.getID());
                            Thread th = ds.createDataset();
                            try {
                                th.join();
                            } catch (InterruptedException ex) {
                                log.info(ex);
                            }
                        } else {
                            log.info("Item is currently unavailable: " + item.getHandle());
                        }
                    }
                }
            }

            // now see if any datasets are orphaned, just in case
            log.info("*** dir: " + dir);
            File datasets[] = new File(dir).listFiles();
            // log.info("*** datasets: " + datasets);

            for (File zip : datasets) {
                if (zip.getName().endsWith(TMP_FILE_NAME_EXT)) {
                    // if file is a temporary file delete it if more than one day old
                    long diff = new Date().getTime() - zip.lastModified();
                    if (diff > 24 * 60 * 60 * 1000) {
                        zip.delete();
                    }
                } else if (!zip.getName().equals("README.txt")) {
                    DatashareItemDataset ds = new DatashareItemDataset(context, zip);
                    if (!itemHandles.contains(ds.getHandle())) {
                        log.info("*** dataset " + zip + " exists with no item. Delete it.");
                        ds.delete();
                    }
                }
            }

        } catch (SQLException ex) {
            log.info(ex);
        } catch (Exception e) {
            log.info(e);
            throw e;

        } finally {
            try {
                if (context != null) {
                    context.complete();
                }
            } catch (SQLException ex) {
                // ignored
            } catch (Exception e) {
                log.info(e);
                throw e;
            }
        }

        log.info("exit");
    }

}

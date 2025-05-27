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
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
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
	private static final String DATASHARE_SCHEMA = "ds";
	private static final String TOMBSTONE_ELEMENT = "withdrawn";
	private static final String TOMBSTONE_SHOW_QUALIFIER = "showtombstone";

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
	 * Check if item has been put under embargo or tombstoned. If so, delete
	 * dataset.
	 */
	public void checkDataset() {
		if (this.exists()) {
			if (hasEmbargo(this.context, this.item) || isTombstoned(this.context, item)) {
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
	 * Delete dataset from system.
	 */
	public void delete() {
		File zip = null;
		if (this.item != null) {
			zip = new File(this.getFullPath());
		} else {
			zip = new File(dir + File.separator + DatashareItemDataset.getFileName(this.handle));
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
	 * Get unique metadata value from DSpace item.
	 * 
	 * @param item      DSpace item.
	 * @param element   Metadata element.
	 * @param qualifier Metadata qualifier.
	 * @param lang      Metadata language.
	 * @param schema    Metadata schema.
	 * @return Metadata value.
	 */
	public static String getUnique(Item item, String element, String qualifier, String lang, String schema) {
		log.info("getUnique() for item: {} with schema: {}, element: {}, qualifier: {}, lang: {}",
				item.getID(), schema, element, qualifier, lang);
		String value = null;
		ItemService itemService = ContentServiceFactory.getInstance().getItemService();

		log.info("itemService: {}", itemService);
		log.info("item: {}", item);

		List<MetadataValue> values = itemService.getMetadata(item, schema, element, qualifier, lang, false);

		log.info("getUnique() found {} values for item: {}", values.size(), item.getID());

		if (values != null && values.size() > 0) {
			value = values.get(0).getValue();
			log.info("getUnique() returning value: {}", value);
		} else {
			log.info("getUnique() no values found, returning null");
		}

		return value;
	}

	/**
	 * @param item DSpace item.
	 * @return Get show tombsomstone metadata value.
	 */

	public static boolean areAllItemBitstreamsAvailable(Context context, Item item) {
		log.info("hasEmbargo: " + hasEmbargo(context, item));
		log.info("isWithdrawn: " + item.isWithdrawn());
		log.info("isTombstoned: " + isTombstoned(context, item));
		return !hasEmbargo(context, item) && !item.isWithdrawn()
				&& !isTombstoned(context, item);
	}

	public static String getShowTombstone(Item item) {
		log.info("getShowTombstone() for item: " + item.getID());
		return getUnique(item, TOMBSTONE_ELEMENT, TOMBSTONE_SHOW_QUALIFIER, Item.ANY, DATASHARE_SCHEMA);
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
		ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

		List<MetadataValue> embargoList = itemService.getMetadataByMetadataString(item,
				configurationService.getProperty("embargo.field.lift"));
		if (embargoList == null || embargoList.size() == 0) {
			hasEmbargo = false;
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

	private static boolean isTombstoned(Context context, Item item) {
		boolean show = false;
		try {
			String tomb = getShowTombstone(item);
			if (tomb != null) {
				show = Boolean.parseBoolean(tomb);
			}

		} catch (Exception ex) {
			throw new RuntimeException("Problem determining access right", ex);
		}

		log.info("isTombstoned(): " + show);
		return show;
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
				throw new RuntimeException(ex);
			} finally {
				try {
					context.complete();
				} catch (SQLException ex) {
					log.warn(ex);
				}
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

				}
				try {
					zos.close();
				} catch (Exception e) {

				}
				// Delete temporary file on exit
				try {
					new File(tmpZip).delete();
				} catch (Exception e) {

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
						if (isTombstoned(context, item)) {
							log.info("Delete tombstoned dataset: " + item.getHandle());
							ds.delete();
						} else {
							log.info("Dataset already exists " + item.getHandle());
						}
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
			} catch (Exception e) {
				log.info(e);
				throw e;
			}
		}

		log.info("exit");
	}

}

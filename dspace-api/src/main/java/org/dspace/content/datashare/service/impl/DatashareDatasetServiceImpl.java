/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare.service.impl;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataField;
import org.dspace.content.MetadataFieldName;
import org.dspace.content.MetadataValue;
import org.dspace.content.datashare.DatashareDataset;
import org.dspace.content.datashare.DatashareItemDataset;
import org.dspace.content.datashare.dao.DatashareDatasetDAO;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.LogHelper;
import org.dspace.event.Event;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DatashareDatasetServiceImpl implements DatashareDatasetService {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareDatasetServiceImpl.class);

    /**
     * Bundles whose bitstreams are bundled into the dataset zip file (see
     * {@link DatashareItemDataset}). A user must be able to READ every bitstream in these bundles
     * to be authorized to download the zip.
     */
    private static final String[] ZIP_BUNDLE_NAMES = { "ORIGINAL", "CC-LICENSE", "LICENSE" };

    @Autowired(required = true)
    private DatashareDatasetDAO datashareDatasetDAO;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private ItemService itemService;

    @Override
    public DatashareDataset insertDatashareDataset(Context context, Item item, String fileName, String cksum) {
        DatashareDataset dataset = null;
        // Delete any current dataset for this item
        deleteDatashareDataset(context, fileName);

        try {
            dataset = createDatashareDataset(context, item, fileName, cksum);
        } catch (SQLException | AuthorizeException e) {
            log.error("Error creating dataset for fileName: " + fileName, e);
        }
        return dataset;
    }

    @Override
    public void deleteDatashareDataset(Context context, String filename) {
        try {

            context.turnOffAuthorisationSystem();
            datashareDatasetDAO.deleteByFileName(context, filename);
        } catch (SQLException e) {
            log.error("Error deleting dataset with fileName: " + filename, e);
        } finally {
            context.restoreAuthSystemState();
        }
    }

    @Override
    public void deleteDatasetForItem(Context context, Item item) {
        if (item == null || item.getHandle() == null) {
            return;
        }
        String fileName = DatashareItemDataset.getFileName(item.getHandle());
        // Drop the database record so the zip is no longer advertised as available...
        deleteDatashareDataset(context, fileName);
        // ...and remove the physical zip so the ds-datasets job regenerates it from the current
        // fileset. This is best-effort: a missing file or unconfigured datasets.path must not
        // break the operation that triggered this (e.g. a bitstream upload/delete).
        deleteDatasetZipFile(item);
    }

    @Override
    public void createDatasetForItem(Context context, Item item) {
        if (item == null || item.getHandle() == null) {
            return;
        }
        try {
            // Generate the zip synchronously using the current context so that, like DataShare 6.x,
            // a freshly archived item immediately has a downloadable "download all files" zip. The
            // DatashareItemDataset guards creation on the item being available (not embargoed/
            // withdrawn) and registers the dataset record.
            new DatashareItemDataset(context, item).createDatasetSync(context);
        } catch (Exception e) {
            // Best-effort: a problem generating the zip (e.g. datasets.path not configured) must not
            // break the operation that triggered this (the item install).
            log.warn("Error creating dataset zip for item " + item.getID(), e);
        }
    }

    /**
     * Best-effort deletion of the physical dataset zip file for the given item. Any problem
     * (datasets.path not configured, file already gone, IO error) is logged and swallowed.
     *
     * @param item the item whose zip file should be deleted
     */
    private void deleteDatasetZipFile(Item item) {
        try {
            String fullPath = DatashareItemDataset.getFullFilePath(item.getHandle());
            File zip = new File(fullPath);
            if (zip.exists() && !zip.delete()) {
                log.warn("Could not delete dataset zip file {} for item {}", fullPath, item.getID());
            }
        } catch (Exception e) {
            log.error("Error deleting dataset zip file for item " + item.getID(), e);
        }
    }

    @Override
    public String fetchDatashareDatasetChecksum(Context context, Item item) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'fetchDatashareDatasetChecksum'");
    }

    @Override
    public boolean isDatashareDatasetZipFileDownloadable(Context context, Item item) {
        try {
            // The zip exposes every file of the item, so it must never be offered to a user who
            // is not authorized to read all of those files.
            if (!isUserAuthorizedToDownloadZip(context, item)) {
                return false;
            }
        } catch (SQLException e) {
            log.error("Error checking download authorization for item: "
                    + (item != null ? item.getID() : null), e);
            return false;
        }
        return findDatashareDatasetByItem(context, item) != null;
    }

    @Override
    public boolean isUserAuthorizedToDownloadZip(Context context, Item item) throws SQLException {
        if (item == null) {
            return false;
        }
        // The dataset zip bundles all of the item's files. A user may only download it when they
        // are authorized to READ every bitstream that would be included in the zip.
        for (String bundleName : ZIP_BUNDLE_NAMES) {
            for (Bundle bundle : itemService.getBundles(item, bundleName)) {
                for (Bitstream bitstream : bundle.getBitstreams()) {
                    if (!authorizeService.authorizeActionBoolean(context, bitstream, Constants.READ)) {
                        log.debug("User not authorized to read bitstream {} of item {}; "
                                + "zip download is forbidden.", bitstream.getID(), item.getID());
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public String fetchDatashareDatasetZipFileLink(Context context, Item item) {
        String downloadLink = "";
        try {
            if (isDatashareDatasetZipFileDownloadable(context, item)) {

                DatashareDataset dataset = findDatashareDatasetByItem(context, item);

                if (dataset != null) {
                    String filePath = DatashareItemDataset.getFullFilePath(item.getHandle());
                    log.info(filePath, filePath);
                    if (filePath != null && !filePath.isEmpty()) {
                        log.info("new File(filePath).exists(): "
                            + new File(filePath).exists());
                        if (new File(filePath).exists()) {
                            downloadLink = DatashareItemDataset.getURL(item) != null
                                ? DatashareItemDataset.getURL(item) : "";
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error fetching download link for item: " + item.getHandle(), e);
        }
        log.info("Download link: " + downloadLink);
        return downloadLink;
    }

    // Private methods
    private DatashareDataset createDatashareDataset(Context context, Item item, String fileName, String checksum)
            throws SQLException, AuthorizeException {
        DatashareDataset datasetObj = new DatashareDataset();
        datasetObj.setItem(item);
        datasetObj.setFileName(fileName);
        datasetObj.setChecksum(checksum);
        DatashareDataset dataset = datashareDatasetDAO.create(context, datasetObj);

        // // Call update to give the item a last modified date. OK this isn't
        // // amazingly efficient but creates don't happen that often.
        // context.turnOffAuthorisationSystem();
        // update(context, dataset);
        // context.restoreAuthSystemState();

        context.addEvent(new Event(Event.CREATE, Constants.DATASHARE_DATASET, dataset.getID(),
                null, new ArrayList<String>()));

        log.info("create Dataset: Dataset id= " + dataset.getID());

        return dataset;

    }

    // Only return DatashareDataset for item if it exists in the file system
    private DatashareDataset findDatashareDatasetByItem(Context context, Item item) {
        try {
            boolean allItemBitstreamsAvailable = DatashareItemDataset.areAllItemBitstreamsAvailable(context, item);
            // If all item bitstreams are not available then we don't want to return a
            // dataset.
            if (!allItemBitstreamsAvailable) {
                log.info("find_Dataset: not_available, Item  = " + item);
                return null;
            }

            DatashareDataset dataset = datashareDatasetDAO.findLatestDatashareDatasetByItem(context, item);
            if (dataset == null) {
                log.info("find_Dataset: not_found, Item uuid = " + null);
                return null;
            }
            log.info("find_Dataset: Item uuid = " + item.getID());
            log.info("find_Dataset: Dataset File = " + dataset.getFileName());

            return dataset;
        } catch (Exception e) {
            log.error("find_Dataset: Item uuid = " + item.getID(), e);
            return null;
        }
    }

    // Unmplemented methods of the interfaces:
    // DSpaceObjectService<DatashareDataset> and DSpaceObjectLegacySupportService<DatashareDataset>
    @Override
    public DatashareDataset find(Context context, UUID uuid) throws SQLException {
        return null;
    }

    @Override
    public String getName(DatashareDataset dso) {
        return dso != null ? dso.getName() : null;
    }

    @Override
    public ArrayList<String> getIdentifiers(Context context, DatashareDataset dso) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getIdentifiers'");
    }

    @Override
    public DSpaceObject getParentObject(Context context, DatashareDataset dso) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getParentObject'");
    }

    @Override
    public DSpaceObject getAdminObject(Context context, DatashareDataset dso, int action) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getAdminObject'");
    }

    @Override
    public String getTypeText(DatashareDataset dso) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getTypeText'");
    }

    @Override
    public List<MetadataValue> getMetadata(DatashareDataset dSpaceObject, String schema, String element,
            String qualifier, String lang) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMetadata'");
    }

    @Override
    public List<MetadataValue> getMetadataByMetadataString(DatashareDataset dSpaceObject, String mdString) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMetadataByMetadataString'");
    }

    @Override
    public String getMetadata(DatashareDataset dSpaceObject, String value) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMetadata'");
    }

    @Override
    public List<MetadataValue> getMetadata(DatashareDataset dSpaceObject, String mdString, String authority) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMetadata'");
    }

    @Override
    public List<MetadataValue> getMetadata(DatashareDataset dSpaceObject, String schema, String element,
            String qualifier, String lang, String authority) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMetadata'");
    }

    @Override
    public List<MetadataValue> addMetadata(Context context, DatashareDataset dso, String schema, String element,
            String qualifier, String lang, List<String> values) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addMetadata'");
    }

    @Override
    public List<MetadataValue> addMetadata(Context context, DatashareDataset dso, String schema, String element,
            String qualifier, String lang, List<String> values, List<String> authorities, List<Integer> confidences)
            throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addMetadata'");
    }

    @Override
    public List<MetadataValue> addMetadata(Context context, DatashareDataset dso, MetadataField metadataField,
            String lang, List<String> values, List<String> authorities, List<Integer> confidences) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addMetadata'");
    }

    @Override
    public MetadataValue addMetadata(Context context, DatashareDataset dso, MetadataField metadataField,
            String language, String value, String authority, int confidence) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addMetadata'");
    }

    @Override
    public MetadataValue addMetadata(Context context, DatashareDataset dso, MetadataField metadataField,
            String language, String value) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addMetadata'");
    }

    @Override
    public List<MetadataValue> addMetadata(Context context, DatashareDataset dso, MetadataField metadataField,
            String language, List<String> values) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addMetadata'");
    }

    @Override
    public MetadataValue addMetadata(Context context, DatashareDataset dso, String schema, String element,
            String qualifier, String lang, String value) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addMetadata'");
    }

    @Override
    public MetadataValue addMetadata(Context context, DatashareDataset dso, String schema, String element,
            String qualifier, String lang, String value, String authority, int confidence, int place)
            throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addMetadata'");
    }

    @Override
    public MetadataValue addMetadata(Context context, DatashareDataset dso, String schema, String element,
            String qualifier, String lang, String value, String authority, int confidence) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addMetadata'");
    }

    @Override
    public void clearMetadata(Context context, DatashareDataset dso, String schema, String element, String qualifier,
            String lang) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'clearMetadata'");
    }

    @Override
    public void removeMetadataValues(Context context, DatashareDataset dso, List<MetadataValue> values)
            throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'removeMetadataValues'");
    }

    @Override
    public String getMetadataFirstValue(DatashareDataset dso, String schema, String element, String qualifier,
            String language) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMetadataFirstValue'");
    }

    @Override
    public String getMetadataFirstValue(DatashareDataset dso, MetadataFieldName field, String language) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMetadataFirstValue'");
    }

    @Override
    public void setMetadataSingleValue(Context context, DatashareDataset dso, String schema, String element,
            String qualifier, String language, String value) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setMetadataSingleValue'");
    }

    @Override
    public void setMetadataSingleValue(Context context, DatashareDataset dso, MetadataFieldName field, String language,
            String value) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setMetadataSingleValue'");
    }

    @Override
    public void updateLastModified(Context context, DatashareDataset dso) throws SQLException, AuthorizeException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'updateLastModified'");
    }

    @Override
    public void update(Context context, DatashareDataset dso) throws SQLException, AuthorizeException {
        datashareDatasetDAO.save(context, dso);
        log.info(LogHelper.getHeader(context, "update_datashare_dataset",
                "fileName=" + dso.getFileName()));
    }

    @Override
    public void delete(Context context, DatashareDataset dso) throws SQLException, AuthorizeException, IOException {
        log.info(LogHelper.getHeader(context, "delete_dso",
                "fileName=" + dso.getFileName()));
        datashareDatasetDAO.delete(context, dso);
    }

    @Override
    public void addAndShiftRightMetadata(Context context, DatashareDataset dso, String schema, String element,
            String qualifier, String lang, String value, String authority, int confidence, int index)
            throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'addAndShiftRightMetadata'");
    }

    @Override
    public void replaceMetadata(Context context, DatashareDataset dso, String schema, String element, String qualifier,
            String lang, String value, String authority, int confidence, int index) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'replaceMetadata'");
    }

    @Override
    public void moveMetadata(Context context, DatashareDataset dso, String schema, String element, String qualifier,
            int from, int to) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'moveMetadata'");
    }

    @Override
    public int getSupportsTypeConstant() {
        return Constants.DATASHARE_DATASET;
    }

    @Override
    public void setMetadataModified(DatashareDataset dso) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setMetadataModified'");
    }

    @Override
    public DatashareDataset findByIdOrLegacyId(Context context, String id) throws SQLException {
        return null;
    }

    @Override
    public DatashareDataset findByLegacyId(Context context, int id) throws SQLException {
        return null;
    }

}

package org.dspace.content.datashare;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataField;
import org.dspace.content.MetadataFieldName;
import org.dspace.content.MetadataValue;
import org.dspace.content.datashare.dao.DatashareDatasetDAO;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.LogHelper;
import org.dspace.event.Event;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DatashareDatasetServiceImpl implements DatashareDatasetService {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareDatasetServiceImpl.class);

    @Autowired(required = true)
    private DatashareDatasetDAO datashareDatasetDAO;

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
    public String fetchDatashareDatasetChecksum(Context context, Item item) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'fetchDatashareDatasetChecksum'");
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

    @Override
    public DatashareDataset find(Context context, UUID uuid) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'find'");
    }

    @Override
    public String getName(DatashareDataset dso) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getName'");
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
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSupportsTypeConstant'");
    }

    @Override
    public void setMetadataModified(DatashareDataset dso) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setMetadataModified'");
    }

    @Override
    public DatashareDataset findByIdOrLegacyId(Context context, String id) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findByIdOrLegacyId'");
    }

    @Override
    public DatashareDataset findByLegacyId(Context context, int id) throws SQLException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findByLegacyId'");
    }


    

}

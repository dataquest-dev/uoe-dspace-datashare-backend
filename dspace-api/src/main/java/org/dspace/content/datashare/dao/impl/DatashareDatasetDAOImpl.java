package org.dspace.content.datashare.dao.impl;

import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.dspace.content.Item;
import org.dspace.content.datashare.DatashareDataset;
import org.dspace.content.datashare.dao.DatashareDatasetDAO;
import org.dspace.core.AbstractHibernateDSODAO;
import org.dspace.core.Context;

import jakarta.persistence.Query;

public class DatashareDatasetDAOImpl extends AbstractHibernateDSODAO<DatashareDataset> implements DatashareDatasetDAO {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareDatasetDAOImpl.class);

    protected DatashareDatasetDAOImpl() {
        super();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<DatashareDataset> findByFileName(Context context, String filename) throws SQLException {
        String queryString = "SELECT ds from DatashareDataset ds where ds.fileName = :filename";
        Query query = createQuery(context, queryString);
        query.setParameter("filename", filename);
        return (List<DatashareDataset>) iterate(query);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<DatashareDataset> findByItem(Context context, Item item) throws SQLException {
        String queryString = "SELECT ds from DatashareDataset ds where ds.item = :item";
        Query query = createQuery(context, queryString);
        query.setParameter("item", item);
        return (List<DatashareDataset>) iterate(query);
    }

    @Override
    public void deleteByFileName(Context context, String filename) throws SQLException {
        String queryString = "Delete from DatashareDataset ds where ds.fileName = :filename";
        Query query = createQuery(context, queryString);
        query.setParameter("filename", filename);
        query.executeUpdate();
        
    }

    @Override
    public void deleteByItem(Context context, Item item) throws SQLException {
        String queryString = "Delete from Dataset ds where ds.fileName = :filename";
        Query query = createQuery(context, queryString);
        query.setParameter("item", item);
        query.executeUpdate();
        
    }

}

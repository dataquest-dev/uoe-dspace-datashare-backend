/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare.dao.impl;

import java.sql.SQLException;
import java.util.List;

import jakarta.persistence.Query;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Item;
import org.dspace.content.datashare.DatashareDataset;
import org.dspace.content.datashare.dao.DatashareDatasetDAO;
import org.dspace.core.AbstractHibernateDSODAO;
import org.dspace.core.Context;

public class DatashareDatasetDAOImpl extends AbstractHibernateDSODAO<DatashareDataset> implements DatashareDatasetDAO {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareDatasetDAOImpl.class);

    protected DatashareDatasetDAOImpl() {
        super();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<DatashareDataset> findByFileName(Context context, String filename) throws SQLException {
        String queryString = "SELECT ddset from DatashareDataset ddset where ddset.fileName = :filename";
        Query query = createQuery(context, queryString);
        query.setParameter("filename", filename);
        return (List<DatashareDataset>) iterate(query);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<DatashareDataset> findByItem(Context context, Item item) throws SQLException {
        String queryString = "SELECT ddset from DatashareDataset ddset where ddset.item = :item";
        Query query = createQuery(context, queryString);
        query.setParameter("item", item);
        return (List<DatashareDataset>) iterate(query);
    }

    @Override
    public void deleteByFileName(Context context, String filename) throws SQLException {
        String queryString = "Delete from DatashareDataset ddset where ddset.fileName = :filename";
        Query query = createQuery(context, queryString);
        query.setParameter("filename", filename);
        query.executeUpdate();

    }

    @Override
    public void deleteByItem(Context context, Item item) throws SQLException {
        String queryString = "Delete from DatashareDataset ddset where ddset.fileName = :filename";
        Query query = createQuery(context, queryString);
        query.setParameter("item", item);
        query.executeUpdate();

    }

    @Override
    public DatashareDataset findLatestDatashareDatasetByItem(Context context, Item item) throws SQLException {
        String queryString = "SELECT ddset FROM DatashareDataset ddset WHERE ddset.item = :item"
                    + " AND ddset.id = (SELECT MAX(d.id) FROM DatashareDataset d WHERE d.item = :item)";
        Query query = createQuery(context, queryString);
        query.setParameter("item", item);
        log.info("queryString: " + queryString);
        return (DatashareDataset) uniqueResult(query);
    }

}

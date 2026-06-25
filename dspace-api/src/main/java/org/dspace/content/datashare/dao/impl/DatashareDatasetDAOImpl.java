/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare.dao.impl;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;

import jakarta.persistence.Query;
import org.dspace.content.Item;
import org.dspace.content.datashare.DatashareDataset;
import org.dspace.content.datashare.dao.DatashareDatasetDAO;
import org.dspace.core.AbstractHibernateDSODAO;
import org.dspace.core.Context;

public class DatashareDatasetDAOImpl extends AbstractHibernateDSODAO<DatashareDataset> implements DatashareDatasetDAO {

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
        // Return the most recently created dataset for the item.
        //
        // We must NOT rank by ddset.id: 'id' is the inherited DSpaceObject UUID, because the numeric
        // auto-increment "id" column is mapped to the legacyId property. Ranking by MAX(ddset.id)
        // therefore picks the highest UUID, not the latest row. On a DSpace 6 -> 8 upgrade the
        // 'dataset' table also keeps legacy rows that have no matching 'dspaceobject' entry; when such
        // a legacy UUID sorts highest, the JOINED-inheritance entity cannot be materialized and the
        // query throws NoResultException. See https://github.com/dataquest-dev/dspace-customers/issues/741.
        //
        // Querying the entity already excludes those un-materializable legacy rows (JOINED inheritance
        // joins 'dataset' to 'dspaceobject'), so we only rank valid datasets and prefer the highest
        // numeric legacy id. That id is NULL on fresh installs (where there is a single dataset per
        // item), which nullsFirst handles safely.
        Query query = createQuery(context, "SELECT ddset FROM DatashareDataset ddset WHERE ddset.item = :item");
        query.setParameter("item", item);
        return list(query).stream()
                .max(Comparator.comparing(DatashareDataset::getLegacyId,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

}

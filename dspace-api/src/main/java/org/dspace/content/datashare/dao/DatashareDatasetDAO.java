/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare.dao;

import java.sql.SQLException;
import java.util.List;

import org.dspace.content.Item;
import org.dspace.content.dao.DSpaceObjectLegacySupportDAO;
import org.dspace.content.datashare.DatashareDataset;
import org.dspace.core.Context;


public interface DatashareDatasetDAO extends DSpaceObjectLegacySupportDAO<DatashareDataset> {

    public List<DatashareDataset> findByFileName(Context context, String filename) throws SQLException;
    public List<DatashareDataset> findByItem(Context context, Item item) throws SQLException;
    public void deleteByFileName(Context context, String filename) throws SQLException;
    public void deleteByItem(Context context, Item item) throws SQLException;
    public DatashareDataset findLatestDatashareDatasetByItem(Context context, Item item) throws SQLException;

}

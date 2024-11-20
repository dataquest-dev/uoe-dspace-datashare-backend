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

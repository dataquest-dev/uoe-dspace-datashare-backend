package org.dspace.content.datashare.service;

import org.dspace.content.Item;
import org.dspace.content.datashare.DatashareDataset;
import org.dspace.content.service.DSpaceObjectLegacySupportService;
import org.dspace.content.service.DSpaceObjectService;
import org.dspace.core.Context;


public interface DatashareDatasetService extends DSpaceObjectService<DatashareDataset>, DSpaceObjectLegacySupportService<DatashareDataset> {

    public void deleteDatashareDataset(Context context, String filename); 

    public DatashareDataset insertDatashareDataset(Context context, Item item, String filename, String cksum);

    public String fetchDatashareDatasetChecksum(Context context, Item item);

    public boolean isDatashareDatasetZipFileDownloadable(Context context, Item item);

    public String fetchDatashareDatasetZipFileLink(Context context, Item item);

}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare.service;

import java.sql.SQLException;

import org.dspace.content.Item;
import org.dspace.content.datashare.DatashareDataset;
import org.dspace.content.service.DSpaceObjectLegacySupportService;
import org.dspace.content.service.DSpaceObjectService;
import org.dspace.core.Context;


public interface DatashareDatasetService
    extends DSpaceObjectService<DatashareDataset>, DSpaceObjectLegacySupportService<DatashareDataset> {

    public void deleteDatashareDataset(Context context, String filename);

    public DatashareDataset insertDatashareDataset(Context context, Item item, String filename, String cksum);

    public String fetchDatashareDatasetChecksum(Context context, Item item);

    public boolean isDatashareDatasetZipFileDownloadable(Context context, Item item);

    public String fetchDatashareDatasetZipFileLink(Context context, Item item);

    /**
     * Check whether the current user of the given context is authorized to download the item's
     * dataset zip file. The zip bundles every file of the item, so a user may only download it
     * when they are authorized to READ all of the bitstreams that would be included.
     *
     * @param context DSpace context containing the current user
     * @param item    the item whose dataset zip is being requested
     * @return {@code true} if the current user may download the zip, {@code false} otherwise
     * @throws SQLException if a database error occurs while resolving the item's bitstreams
     */
    public boolean isUserAuthorizedToDownloadZip(Context context, Item item) throws SQLException;

    /**
     * Delete the dataset zip for the given item: both the database record and the physical zip
     * file on disk. This is used when the item's fileset changes (a file is added, replaced or
     * removed) so that the stale "download all files" zip is dropped and regenerated from the
     * current files by the {@code ds-datasets} job. The physical file is deleted on a best-effort
     * basis; failure to delete it does not prevent the database record from being removed.
     *
     * @param context DSpace context
     * @param item    the item whose dataset zip should be removed; ignored when {@code null} or
     *                when it has no handle yet
     */
    public void deleteDatasetForItem(Context context, Item item);

    /**
     * Generate the dataset zip for the given item and register its database record, using the
     * supplied context. This is used when a new item is archived (installed) so that the
     * "download all files" zip is available immediately, mirroring the DataShare 6.x behaviour
     * (rather than waiting for the next {@code ds-datasets} batch run). The zip is only created
     * when all of the item's bitstreams may be exposed (item not under embargo and not withdrawn);
     * any problem is logged and swallowed so it cannot break the operation that triggered it.
     *
     * @param context DSpace context
     * @param item    the item whose dataset zip should be generated; ignored when {@code null} or
     *                when it has no handle yet
     */
    public void createDatasetForItem(Context context, Item item);

}

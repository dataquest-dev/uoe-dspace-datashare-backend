/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare.dao.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.UUID;

import jakarta.persistence.Query;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.datashare.DatashareDataset;
import org.dspace.content.datashare.dao.DatashareDatasetDAO;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.core.CoreHelpers;
import org.dspace.core.HibernateDBConnection;
import org.dspace.utils.DSpace;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for {@link DatashareDatasetDAO#findLatestDatashareDatasetByItem} reproducing
 * <a href="https://github.com/dataquest-dev/dspace-customers/issues/741">issue #741</a>.
 *
 * <p>On a DSpace 6 -&gt; 8 upgrade the {@code dataset} table is carried over with its legacy rows in
 * place; those rows have a UUID but no matching {@code dspaceobject} entry (the
 * {@code CREATE TABLE IF NOT EXISTS} migration does not touch the pre-existing table, so its foreign
 * key is never added). The buggy lookup ranked datasets by {@code MAX(ddset.id)} where {@code id} is
 * the inherited {@link org.dspace.content.DSpaceObject} <em>UUID</em> (the numeric auto-increment
 * column is mapped to {@code legacyId}). When such a legacy UUID sorted highest, the JOINED-inheritance
 * entity could not be materialized and the query threw {@code NoResultException}, so the item's
 * "download all" zip link silently disappeared.</p>
 */
public class DatashareDatasetLatestLookupIT extends AbstractIntegrationTestWithDatabase {

    /** Sorts higher than any randomly generated dataset UUID, so a buggy MAX(uuid) always picks it. */
    private static final UUID MAX_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private final DatashareDatasetDAO datasetDAO =
            new DSpace().getServiceManager().getServicesByType(DatashareDatasetDAO.class).get(0);
    private final DatashareDatasetService datasetService =
            ContentServiceFactory.getInstance().getDatashareDatasetService();

    private Item item;
    private DatashareDataset currentDataset;

    @Before
    public void createDatasetItem() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();
        item = ItemBuilder.createItem(context, collection)
                .withTitle("Datashare item")
                .withIssueDate("2024-01-17")
                .build();
        // The valid, current dataset created by the app. Going through the service means it gets a
        // matching dspaceobject row, so it can be materialized as a DatashareDataset entity.
        currentDataset = datasetService.insertDatashareDataset(context, item, "DS_current.zip", "checksum-current");
        context.restoreAuthSystemState();
        context.commit();
    }

    @After
    public void cleanupDatasetRows() throws Exception {
        context.turnOffAuthorisationSystem();
        try {
            // Drop the dangling legacy row first: while it references item_id the builder teardown
            // cannot delete the item, and it has no dspaceobject row to remove via the service.
            deleteDatasetRow(MAX_UUID);
            datasetService.deleteDatashareDataset(context, "DS_current.zip");
            context.commit();
        } finally {
            setReferentialIntegrity(true);
            context.restoreAuthSystemState();
        }
    }

    /**
     * Issue #741: an item that also has a legacy {@code dataset} row whose UUID sorts highest (and which
     * has no {@code dspaceobject} entry) must still resolve to its valid, current dataset - the lookup
     * must not throw {@code NoResultException} nor return the un-materializable legacy row.
     */
    @Test
    public void resolvesValidDatasetEvenWhenAnUnmaterializableLegacyRowSortsHighest() throws Exception {
        insertDanglingLegacyDatasetRow(MAX_UUID, 1);

        DatashareDataset latest = datasetDAO.findLatestDatashareDatasetByItem(context, item);

        assertNotNull("lookup must not throw and must return the valid, materializable dataset", latest);
        assertEquals("must return the current dataset, not the legacy row that merely sorts highest",
                currentDataset.getID(), latest.getID());
    }

    /** Sanity check for the common (fresh-install) case: a single, valid dataset is returned. */
    @Test
    public void returnsTheOnlyDatasetWhenNoLegacyRowExists() throws Exception {
        DatashareDataset latest = datasetDAO.findLatestDatashareDatasetByItem(context, item);

        assertNotNull(latest);
        assertEquals(currentDataset.getID(), latest.getID());
    }

    /**
     * Insert a legacy {@code dataset} row exactly like the DSpace 6 -&gt; 8 upgrade leaves behind: same
     * item, a UUID that sorts highest, and no matching {@code dspaceobject} row. The test schema (unlike
     * the upgraded production table) has the {@code dataset_uuid_fkey} foreign key, so H2 referential
     * integrity is disabled for the insert.
     */
    private void insertDanglingLegacyDatasetRow(UUID uuid, Integer legacyId) throws Exception {
        HibernateDBConnection dbc = (HibernateDBConnection) CoreHelpers.getDBConnection(context);
        setReferentialIntegrity(false);
        Query insert = dbc.getSession().createNativeQuery(
                "INSERT INTO dataset (uuid, id, item_id, file_name, checksum, checksum_algorithm)"
                        + " VALUES (:uuid, :id, :item, :file, :checksum, :algo)");
        insert.setParameter("uuid", uuid);
        insert.setParameter("id", legacyId);
        insert.setParameter("item", item.getID());
        insert.setParameter("file", "DS_current.zip");
        insert.setParameter("checksum", "legacy-checksum");
        insert.setParameter("algo", "MD5");
        insert.executeUpdate();
        // Re-enabling does not re-validate existing rows in H2, so the dangling row survives for the test.
        setReferentialIntegrity(true);
    }

    private void deleteDatasetRow(UUID uuid) throws Exception {
        HibernateDBConnection dbc = (HibernateDBConnection) CoreHelpers.getDBConnection(context);
        Query delete = dbc.getSession().createNativeQuery("DELETE FROM dataset WHERE uuid = :uuid");
        delete.setParameter("uuid", uuid);
        delete.executeUpdate();
    }

    private void setReferentialIntegrity(boolean enabled) throws Exception {
        HibernateDBConnection dbc = (HibernateDBConnection) CoreHelpers.getDBConnection(context);
        dbc.getSession().createNativeQuery("SET REFERENTIAL_INTEGRITY " + (enabled ? "TRUE" : "FALSE"))
                .executeUpdate();
    }
}

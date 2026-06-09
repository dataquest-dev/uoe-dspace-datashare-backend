/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.InstallItemService;
import org.dspace.content.service.ItemService;
import org.junit.Test;

/**
 * End-to-end integration test for the DataShare archive-time metadata behaviour, exercised through the
 * <b>real item-install event dispatch</b> (not by invoking the consumers directly).
 *
 * <p>This is the coverage that was missing: {@code spatialcoverage} and {@code temporalcoverage} are
 * registered on the default event dispatcher (see the test {@code local.cfg}), so installing an item fires
 * {@code Item+Install} and the consumers run exactly as they do in production. A regression that stops the
 * transforms running on a real archive (e.g. a consumer dropped from the dispatcher, or a persistence
 * problem in {@code end()}) is caught here, whereas the per-consumer unit ITs - which call
 * {@code consume()}/{@code end()} by hand - would still pass.</p>
 *
 * <p>It asserts that archiving an item:</p>
 * <ul>
 *   <li>stamps {@code dc.date.available} (DSpace 6 behaviour restored in {@code InstallItemServiceImpl});</li>
 *   <li>merges {@code dc.subject.ddc} (Spatial Coverage: Country) into {@code dc.coverage.spatial} and clears it;</li>
 *   <li>encodes {@code dc.coverage.startDate}/{@code endDate} into {@code dc.coverage.temporal} and clears them;</li>
 *   <li>assigns the owning collection (so the item page breadcrumb shows the collection).</li>
 * </ul>
 */
public class DatashareArchiveInstallIT extends AbstractIntegrationTestWithDatabase {

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final InstallItemService installItemService =
            ContentServiceFactory.getInstance().getInstallItemService();

    private List<String> values(Item item, String field) {
        return itemService.getMetadataByMetadataString(item, field).stream()
                .map(MetadataValue::getValue).collect(Collectors.toList());
    }

    @Test
    public void realInstallAppliesAllArchiveTransforms() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();
        WorkspaceItem wsi = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("Datashare archive item").withIssueDate("2024-01-17").build();
        Item item = wsi.getItem();
        // Transient submission-time fields, exactly as DatashareSpatialAndTemporalStep stores them while editing.
        itemService.addMetadata(context, item, "dc", "subject", "ddc", null, "GB");
        itemService.addMetadata(context, item, "dc", "coverage", "spatial", null, "Edinburgh");
        itemService.addMetadata(context, item, "dc", "coverage", "startDate", null, "2021");
        itemService.addMetadata(context, item, "dc", "coverage", "endDate", null, "2023");
        itemService.update(context, item);

        // Real archive: installItem fires Item+Install; dispatchEvents runs the registered consumers, as in prod.
        Item installed = installItemService.installItem(context, wsi);
        context.dispatchEvents();
        context.restoreAuthSystemState();

        // dc.date.available stamped on archive (non-embargo item) - exactly one value, no duplicates.
        assertEquals("exactly one dc.date.available on archive", 1, values(installed, "dc.date.available").size());

        // Spatial Coverage: Country merged into dc.coverage.spatial; dc.subject.ddc cleared.
        assertTrue("dc.subject.ddc must be cleared on archive", values(installed, "dc.subject.ddc").isEmpty());
        List<String> spatial = values(installed, "dc.coverage.spatial");
        assertTrue("dc.coverage.spatial keeps the place", spatial.contains("Edinburgh"));
        assertTrue("dc.coverage.spatial gains the country", spatial.contains("GB"));

        // Temporal start/end encoded into a single dc.coverage.temporal; individual fields cleared.
        assertEquals(List.of("start=2021; end=2023; scheme=W3C-DTF"), values(installed, "dc.coverage.temporal"));
        assertTrue("dc.coverage.startDate cleared", values(installed, "dc.coverage.startDate").isEmpty());
        assertTrue("dc.coverage.endDate cleared", values(installed, "dc.coverage.endDate").isEmpty());

        // Owning collection assigned (drives the item-page collection breadcrumb).
        assertEquals(collection, installed.getOwningCollection());
    }
}

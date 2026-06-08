/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.embargo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.dspace.AbstractUnitTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DCDate;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.CommunityService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.embargo.factory.EmbargoServiceFactory;
import org.dspace.embargo.service.EmbargoService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link EmbargoServiceImpl}, focused on the DataShare (UoE) requirement that the
 * embargo terms field (embargo.field.terms = dc.date.embargo) is removed when the embargo is lifted,
 * and the lift field (embargo.field.lift = dc.date.available) is updated to the real availability date.
 */
public class EmbargoServiceImplTest extends AbstractUnitTest {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(EmbargoServiceImplTest.class);

    protected CommunityService communityService = ContentServiceFactory.getInstance().getCommunityService();
    protected CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
    protected ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    protected WorkspaceItemService workspaceItemService =
        ContentServiceFactory.getInstance().getWorkspaceItemService();
    protected EmbargoService embargoService = EmbargoServiceFactory.getInstance().getEmbargoService();

    /** A clearly future embargo lift date used by the tests. */
    private static final String EMBARGO_DATE = "2050-01-01";

    private Collection collection;
    private Community owningCommunity;

    @Before
    @Override
    public void init() {
        super.init();
        try {
            context.turnOffAuthorisationSystem();
            this.owningCommunity = communityService.create(null, context);
            this.collection = collectionService.create(context, owningCommunity);
            context.restoreAuthSystemState();
        } catch (SQLException | AuthorizeException ex) {
            log.error("Error in init", ex);
            fail("Error in init: " + ex.getMessage());
        }
    }

    @After
    @Override
    public void destroy() {
        try {
            context.turnOffAuthorisationSystem();
            communityService.delete(context, owningCommunity);
            context.restoreAuthSystemState();
        } catch (Exception ex) {
            log.error("Error in destroy", ex);
            fail("Error in destroy: " + ex.getMessage());
        }
        super.destroy();
    }

    private Item createItem() throws SQLException, AuthorizeException {
        WorkspaceItem wi = workspaceItemService.create(context, collection, false);
        return wi.getItem();
    }

    private String today() {
        // DCDate.getCurrent() includes a time component; compare only the yyyy-MM-dd part
        return DCDate.getCurrent().toString().substring(0, 10);
    }

    /**
     * Lifting an embargo must remove the embargo terms field (dc.date.embargo) and set
     * dc.date.available (the lift field) to today.
     */
    @Test
    public void testLiftEmbargo_removesEmbargoTermsAndUpdatesAvailable() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createItem();

        // Simulate an embargoed item: the user-supplied terms (dc.date.embargo) and the
        // computed lift date (dc.date.available) both hold the embargo date.
        itemService.addMetadata(context, item, "dc", "date", "embargo", null, EMBARGO_DATE);
        itemService.addMetadata(context, item, "dc", "date", "available", null, EMBARGO_DATE);
        itemService.update(context, item);

        // Sanity check: terms present before lifting.
        assertEquals(1, itemService.getMetadata(item, "dc", "date", "embargo", Item.ANY).size());

        embargoService.liftEmbargo(context, item);
        context.restoreAuthSystemState();

        // Requirement: dc.date.embargo must be removed when the embargo is lifted.
        assertEquals("dc.date.embargo must be removed when the embargo is lifted",
                     0, itemService.getMetadata(item, "dc", "date", "embargo", Item.ANY).size());

        // dc.date.available must be present exactly once and updated to today.
        List<MetadataValue> available = itemService.getMetadata(item, "dc", "date", "available", Item.ANY);
        assertEquals("dc.date.available must be set exactly once on lift", 1, available.size());
        assertEquals("dc.date.available must be updated to today on lift",
                     today(), available.get(0).getValue().substring(0, 10));
    }

    /**
     * Full round-trip: set an embargo (which populates dc.date.available from the terms) and then
     * lift it. After lifting, dc.date.embargo must be gone and dc.date.available must be today.
     */
    @Test
    public void testSetThenLiftEmbargo_removesEmbargoTerms() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = createItem();

        // user-supplied embargo terms
        itemService.addMetadata(context, item, "dc", "date", "embargo", null, EMBARGO_DATE);
        itemService.update(context, item);

        // setEmbargo computes the lift date (dc.date.available) from the terms
        embargoService.setEmbargo(context, item);
        List<MetadataValue> availableUnderEmbargo =
            itemService.getMetadata(item, "dc", "date", "available", Item.ANY);
        assertEquals("setEmbargo should populate dc.date.available", 1, availableUnderEmbargo.size());
        assertEquals(new DCDate(EMBARGO_DATE).toString(), availableUnderEmbargo.get(0).getValue());

        // Now lift the embargo
        embargoService.liftEmbargo(context, item);
        context.restoreAuthSystemState();

        assertEquals("dc.date.embargo must be removed when the embargo is lifted",
                     0, itemService.getMetadata(item, "dc", "date", "embargo", Item.ANY).size());
        List<MetadataValue> available = itemService.getMetadata(item, "dc", "date", "available", Item.ANY);
        assertEquals("dc.date.available must be set exactly once on lift", 1, available.size());
        assertEquals("dc.date.available must be updated to today on lift",
                     today(), available.get(0).getValue().substring(0, 10));
    }
}

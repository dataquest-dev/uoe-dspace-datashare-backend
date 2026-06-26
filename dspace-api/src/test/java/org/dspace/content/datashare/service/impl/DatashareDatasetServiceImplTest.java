/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare.service.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.authorize.service.ResourcePolicyService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.datashare.DatashareDataset;
import org.dspace.content.datashare.DatashareItemDataset;
import org.dspace.content.datashare.dao.DatashareDatasetDAO;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.GroupService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for the authorization logic that protects the Datashare "download all files"
 * (zip) feature. See https://github.com/dataquest-dev/dspace-customers/issues/647.
 * <p>
 * These also cover the performance fix for the per-bitstream authorization storm: the decision is
 * computed without the per-file workflow/workspace probing of {@code AuthorizeService}, the user's
 * group membership is resolved once, and the result is cached per (item, eperson).
 */
@RunWith(MockitoJUnitRunner.class)
public class DatashareDatasetServiceImplTest {

    @InjectMocks
    private DatashareDatasetServiceImpl datashareDatasetService;

    @Mock
    private AuthorizeService authorizeService;

    @Mock
    private ItemService itemService;

    @Mock
    private ResourcePolicyService resourcePolicyService;

    @Mock
    private GroupService groupService;

    @Mock
    private DatashareDatasetDAO datashareDatasetDAO;

    @Mock
    private Context context;

    /** The single group the (anonymous) test user is a member of. */
    private Group userGroup;

    @Before
    public void setUp() throws Exception {
        userGroup = mock(Group.class);
        // No IP/special groups in play by default, so the (item, eperson) cache is used.
        lenient().when(context.getSpecialGroups()).thenReturn(Collections.emptyList());
        // The current user (null == anonymous) is a member of exactly userGroup.
        lenient().when(groupService.allMemberGroupsSet(context, null)).thenReturn(Set.of(userGroup));
    }

    /** A date-valid READ policy granted to the given group. */
    private ResourcePolicy readPolicyForGroup(Group group) {
        ResourcePolicy rp = mock(ResourcePolicy.class);
        when(rp.getGroup()).thenReturn(group);
        when(resourcePolicyService.isDateValid(rp)).thenReturn(true);
        return rp;
    }

    /** Stub the item so it has a single ORIGINAL bundle holding the given bitstreams. */
    private Item itemWithOriginalBitstreams(Bitstream... bitstreams) throws Exception {
        Item item = mock(Item.class);
        when(item.getID()).thenReturn(UUID.randomUUID());
        Bundle original = mock(Bundle.class);
        when(itemService.getBundles(item, "ORIGINAL")).thenReturn(List.of(original));
        when(original.getBitstreams()).thenReturn(List.of(bitstreams));
        when(authorizeService.isAdmin(context, item)).thenReturn(false);
        return item;
    }

    @Test
    public void authorizedWhenAllBitstreamsAreReadable() throws Exception {
        Bitstream b1 = mock(Bitstream.class);
        Bitstream b2 = mock(Bitstream.class);
        Item item = itemWithOriginalBitstreams(b1, b2);
        // All three zip bundles are walked when everything is readable.
        when(itemService.getBundles(item, "CC-LICENSE")).thenReturn(Collections.emptyList());
        when(itemService.getBundles(item, "LICENSE")).thenReturn(Collections.emptyList());
        ResourcePolicy p1 = readPolicyForGroup(userGroup);
        ResourcePolicy p2 = readPolicyForGroup(userGroup);
        when(resourcePolicyService.find(context, b1, Constants.READ)).thenReturn(List.of(p1));
        when(resourcePolicyService.find(context, b2, Constants.READ)).thenReturn(List.of(p2));

        assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
    }

    @Test
    public void forbiddenWhenAnyBitstreamIsNotReadable() throws Exception {
        Bitstream readable = mock(Bitstream.class);
        Bitstream restricted = mock(Bitstream.class);
        Item item = itemWithOriginalBitstreams(readable, restricted);
        Group otherGroup = mock(Group.class);
        ResourcePolicy readablePolicy = readPolicyForGroup(userGroup);
        // Restricted to a group the user is not a member of.
        ResourcePolicy restrictedPolicy = readPolicyForGroup(otherGroup);
        when(resourcePolicyService.find(context, readable, Constants.READ)).thenReturn(List.of(readablePolicy));
        when(resourcePolicyService.find(context, restricted, Constants.READ)).thenReturn(List.of(restrictedPolicy));

        assertFalse(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
    }

    @Test
    public void forbiddenWhenBitstreamHasNoReadPolicy() throws Exception {
        Bitstream restricted = mock(Bitstream.class);
        Item item = itemWithOriginalBitstreams(restricted);
        when(resourcePolicyService.find(context, restricted, Constants.READ))
                .thenReturn(Collections.emptyList());

        assertFalse(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
    }

    @Test
    public void forbiddenWhenBitstreamReadPolicyIsNotDateValid() throws Exception {
        // Embargoed / expired policy: present but not currently valid -> denied (embargo preserved).
        Bitstream embargoed = mock(Bitstream.class);
        Item item = itemWithOriginalBitstreams(embargoed);
        ResourcePolicy rp = mock(ResourcePolicy.class);
        when(resourcePolicyService.isDateValid(rp)).thenReturn(false);
        when(resourcePolicyService.find(context, embargoed, Constants.READ)).thenReturn(List.of(rp));

        assertFalse(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
    }

    @Test
    public void administratorIsAuthorizedWithoutEnumeratingBitstreams() throws Exception {
        Item item = mock(Item.class);
        when(item.getID()).thenReturn(UUID.randomUUID());
        when(authorizeService.isAdmin(context, item)).thenReturn(true);

        assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));

        // Administrators bypass resource policies: we must authorize at the item level and never
        // walk the item's bundles/bitstreams nor load any per-bitstream policy.
        verifyNoInteractions(itemService);
        verify(resourcePolicyService, never()).find(any(Context.class), any(), eq(Constants.READ));
    }

    @Test
    public void forbiddenWhenItemIsNull() throws Exception {
        assertFalse(datashareDatasetService.isUserAuthorizedToDownloadZip(context, null));
    }

    @Test
    public void decisionIsCachedPerItemAndUser() throws Exception {
        Bitstream b1 = mock(Bitstream.class);
        Item item = itemWithOriginalBitstreams(b1);
        when(itemService.getBundles(item, "CC-LICENSE")).thenReturn(Collections.emptyList());
        when(itemService.getBundles(item, "LICENSE")).thenReturn(Collections.emptyList());
        ResourcePolicy p1 = readPolicyForGroup(userGroup);
        when(resourcePolicyService.find(context, b1, Constants.READ)).thenReturn(List.of(p1));

        // The endpoint is hit on every page view: the second call for the same (item, user) must be
        // served from cache rather than re-running the per-bitstream authorization.
        assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
        assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));

        verify(resourcePolicyService, times(1)).find(context, b1, Constants.READ);
        verify(groupService, times(1)).allMemberGroupsSet(context, null);
    }

    @Test
    public void groupMembershipResolvedOnceRegardlessOfFileCount() throws Exception {
        // Many readable files must not multiply the group-membership lookups.
        Bitstream[] bitstreams = new Bitstream[50];
        List<Bitstream> bs = new ArrayList<>();
        for (int i = 0; i < bitstreams.length; i++) {
            bitstreams[i] = mock(Bitstream.class);
            bs.add(bitstreams[i]);
            ResourcePolicy policy = readPolicyForGroup(userGroup);
            when(resourcePolicyService.find(context, bitstreams[i], Constants.READ)).thenReturn(List.of(policy));
        }
        Item item = mock(Item.class);
        when(item.getID()).thenReturn(UUID.randomUUID());
        when(authorizeService.isAdmin(context, item)).thenReturn(false);
        Bundle original = mock(Bundle.class);
        when(itemService.getBundles(item, "ORIGINAL")).thenReturn(List.of(original));
        when(itemService.getBundles(item, "CC-LICENSE")).thenReturn(Collections.emptyList());
        when(itemService.getBundles(item, "LICENSE")).thenReturn(Collections.emptyList());
        when(original.getBitstreams()).thenReturn(bs);

        assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));

        verify(groupService, times(1)).allMemberGroupsSet(context, null);
    }

    @Test
    public void zipContentAvailabilityIsCachedPerItem() throws Exception {
        // The downloadable check walks every bitstream's anonymous readability
        // (areAllItemBitstreamsAvailable). That is reached on every page view and must be cached per
        // item, otherwise the per-file storm simply moves from authorization to availability.
        Item item = mock(Item.class);
        when(item.getID()).thenReturn(UUID.randomUUID());
        // Administrator -> authorized, so we reach the availability check.
        when(authorizeService.isAdmin(context, item)).thenReturn(true);
        when(datashareDatasetDAO.findLatestDatashareDatasetByItem(context, item))
                .thenReturn(mock(DatashareDataset.class));

        try (MockedStatic<DatashareItemDataset> mocked = mockStatic(DatashareItemDataset.class)) {
            mocked.when(() -> DatashareItemDataset.areAllItemBitstreamsAvailable(context, item)).thenReturn(true);

            assertTrue(datashareDatasetService.isDatashareDatasetZipFileDownloadable(context, item));
            assertTrue(datashareDatasetService.isDatashareDatasetZipFileDownloadable(context, item));

            // The expensive per-bitstream anonymous-readability walk ran only once.
            mocked.verify(() -> DatashareItemDataset.areAllItemBitstreamsAvailable(context, item), times(1));
        }
    }

    @Test
    public void notDownloadableWhenUserIsNotAuthorized() throws Exception {
        Bitstream restricted = mock(Bitstream.class);
        Item item = itemWithOriginalBitstreams(restricted);
        when(resourcePolicyService.find(context, restricted, Constants.READ))
                .thenReturn(Collections.emptyList());

        assertFalse(datashareDatasetService.isDatashareDatasetZipFileDownloadable(context, item));
        // Authorization is checked first, so we never reach the dataset lookup.
        verifyNoInteractions(datashareDatasetDAO);
    }

    @Test
    public void deleteDatasetForItemRemovesTheDatabaseRecord() throws Exception {
        Item item = mock(Item.class);
        when(item.getHandle()).thenReturn("123456789/8967");

        datashareDatasetService.deleteDatasetForItem(context, item);

        // The DB record for the item's zip is dropped so it is no longer offered for download
        // and gets regenerated from the current fileset by the ds-datasets job.
        verify(datashareDatasetDAO).deleteByFileName(context, "DS_123456789_8967.zip");
    }

    @Test
    public void deleteDatasetForItemIgnoresNullItem() throws Exception {
        datashareDatasetService.deleteDatasetForItem(context, null);

        verifyNoInteractions(datashareDatasetDAO);
    }

    @Test
    public void deleteDatasetForItemIgnoresItemWithoutHandle() throws Exception {
        Item item = mock(Item.class);
        when(item.getHandle()).thenReturn(null);

        datashareDatasetService.deleteDatasetForItem(context, item);

        verifyNoInteractions(datashareDatasetDAO);
    }
}

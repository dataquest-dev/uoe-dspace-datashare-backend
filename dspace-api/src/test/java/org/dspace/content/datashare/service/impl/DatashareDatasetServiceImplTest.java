/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
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

import java.io.File;
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

    /**
     * Stub the item so it has a single ORIGINAL bundle holding the given bitstreams. Stubs are lenient
     * because the different code paths under test reach different subsets of them (e.g. the
     * special-groups bypass never builds the cache key, so never reads the id).
     */
    private Item itemWithOriginalBitstreams(Bitstream... bitstreams) throws Exception {
        Item item = mock(Item.class);
        lenient().when(item.getID()).thenReturn(UUID.randomUUID());
        // The zip is only authorized for installed (archived) items - the per-bitstream path is only
        // reached for those.
        lenient().when(item.isArchived()).thenReturn(true);
        Bundle original = mock(Bundle.class);
        lenient().when(itemService.getBundles(item, "ORIGINAL")).thenReturn(List.of(original));
        lenient().when(original.getBitstreams()).thenReturn(List.of(bitstreams));
        lenient().when(authorizeService.isAdmin(context, item)).thenReturn(false);
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
        when(item.isArchived()).thenReturn(true);
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
    public void nonArchivedItemIsNotAuthorized() throws Exception {
        // A non-installed (workspace/workflow/draft) item has no dataset zip. We deny without
        // evaluating per-bitstream policies, so we never honour custom policies AuthorizeService would
        // ignore for such an item (DS-2614).
        Item item = mock(Item.class);
        when(item.getID()).thenReturn(UUID.randomUUID());
        when(authorizeService.isAdmin(context, item)).thenReturn(false);
        when(item.isArchived()).thenReturn(false);

        assertFalse(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));

        verifyNoInteractions(itemService);
        verify(resourcePolicyService, never()).find(any(Context.class), any(), eq(Constants.READ));
    }

    @Test
    public void specialGroupsBypassTheCache() throws Exception {
        // When the request carries special (e.g. IP-based) groups the decision is session-specific and
        // must NOT be shared via the coarse (item, eperson) cache: it is recomputed every call.
        Bitstream b1 = mock(Bitstream.class);
        Item item = itemWithOriginalBitstreams(b1);
        when(itemService.getBundles(item, "CC-LICENSE")).thenReturn(Collections.emptyList());
        when(itemService.getBundles(item, "LICENSE")).thenReturn(Collections.emptyList());
        when(context.getSpecialGroups()).thenReturn(List.of(mock(Group.class)));
        ResourcePolicy p1 = readPolicyForGroup(userGroup);
        when(resourcePolicyService.find(context, b1, Constants.READ)).thenReturn(List.of(p1));

        assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
        assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));

        verify(resourcePolicyService, times(2)).find(context, b1, Constants.READ);
    }

    @Test
    public void deletingTheDatasetInvalidatesTheCachedAuthorization() throws Exception {
        Bitstream b1 = mock(Bitstream.class);
        Item item = itemWithOriginalBitstreams(b1);
        when(item.getHandle()).thenReturn("123456789/1");
        when(itemService.getBundles(item, "CC-LICENSE")).thenReturn(Collections.emptyList());
        when(itemService.getBundles(item, "LICENSE")).thenReturn(Collections.emptyList());
        ResourcePolicy p1 = readPolicyForGroup(userGroup);
        when(resourcePolicyService.find(context, b1, Constants.READ)).thenReturn(List.of(p1));

        try (MockedStatic<DatashareItemDataset> mocked = mockStatic(DatashareItemDataset.class)) {
            mocked.when(() -> DatashareItemDataset.getFileName("123456789/1")).thenReturn("DS_123456789_1.zip");
            mocked.when(() -> DatashareItemDataset.getFullFilePath("123456789/1"))
                    .thenReturn("/does/not/exist/DS_123456789_1.zip");

            assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
            // A fileset/policy change drops the item's dataset, which must evict the cached decision...
            datashareDatasetService.deleteDatasetForItem(context, item);
            assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
        }

        // ...so the second authorization is recomputed, not served from the (now stale) cache.
        verify(resourcePolicyService, times(2)).find(context, b1, Constants.READ);
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
    public void fetchLinkResolvesDatasetOnlyOnce() throws Exception {
        // fetchDatashareDatasetZipFileLink used to resolve the dataset twice per request (via the
        // downloadable check and again directly). It must now hit the DAO at most once.
        Item item = mock(Item.class);
        when(item.getID()).thenReturn(UUID.randomUUID());
        when(item.getHandle()).thenReturn("123456789/9");
        when(authorizeService.isAdmin(context, item)).thenReturn(true);
        when(datashareDatasetDAO.findLatestDatashareDatasetByItem(context, item))
                .thenReturn(mock(DatashareDataset.class));

        try (MockedStatic<DatashareItemDataset> mocked = mockStatic(DatashareItemDataset.class)) {
            mocked.when(() -> DatashareItemDataset.areAllItemBitstreamsAvailable(context, item)).thenReturn(true);
            // No physical zip on disk -> link stays empty, but the single DAO lookup still happened.
            mocked.when(() -> DatashareItemDataset.getFullFilePath("123456789/9"))
                    .thenReturn("/does/not/exist/DS.zip");

            datashareDatasetService.fetchDatashareDatasetZipFileLink(context, item);

            verify(datashareDatasetDAO, times(1)).findLatestDatashareDatasetByItem(context, item);
        }
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

    // Cache-busting of the "download all files" zip link: a regenerated zip must change the link so
    // the browser refetches it instead of serving the previous one from cache.

    @Test
    public void appendCacheBustVersionAddsQueryParamWhenNoQueryPresent() {
        assertEquals("http://host/download/DS_1_2.zip?v=abc123",
                DatashareDatasetServiceImpl.appendCacheBustVersion("http://host/download/DS_1_2.zip", "abc123"));
    }

    @Test
    public void appendCacheBustVersionUsesAmpersandWhenQueryAlreadyPresent() {
        assertEquals("http://host/download/DS_1_2.zip?a=b&v=abc123",
                DatashareDatasetServiceImpl.appendCacheBustVersion("http://host/download/DS_1_2.zip?a=b", "abc123"));
    }

    @Test
    public void appendCacheBustVersionUrlEncodesTheToken() {
        // A token is never expected to contain unsafe characters (md5 hex / epoch millis), but the
        // helper must still encode defensively so a stray character can never break the URL.
        assertEquals("http://host/DS.zip?v=a%2Fb+c",
                DatashareDatasetServiceImpl.appendCacheBustVersion("http://host/DS.zip", "a/b c"));
    }

    @Test
    public void appendCacheBustVersionReturnsUrlUnchangedWhenTokenBlankOrNull() {
        assertEquals("http://host/DS.zip", DatashareDatasetServiceImpl.appendCacheBustVersion("http://host/DS.zip",
                null));
        assertEquals("http://host/DS.zip", DatashareDatasetServiceImpl.appendCacheBustVersion("http://host/DS.zip",
                "   "));
    }

    @Test
    public void appendCacheBustVersionReturnsUrlUnchangedWhenUrlBlankOrNull() {
        assertNull(DatashareDatasetServiceImpl.appendCacheBustVersion(null, "abc"));
        assertEquals("", DatashareDatasetServiceImpl.appendCacheBustVersion("", "abc"));
    }

    /**
     * Drive {@link DatashareDatasetServiceImpl#fetchDatashareDatasetZipFileLink} for an authorized
     * (admin) user against an item whose dataset row carries {@code checksum} and whose physical zip
     * is {@code zipFile}, with the static download base URL stubbed to {@code baseUrl}. Returns the
     * link the endpoint produced.
     */
    private String fetchLinkWith(String baseUrl, String checksum, File zipFile) throws Exception {
        Item item = mock(Item.class);
        lenient().when(item.getID()).thenReturn(UUID.randomUUID());
        when(item.getHandle()).thenReturn("123456789/42");
        // Admin bypasses per-bitstream authorization, keeping this test focused on the link itself.
        when(authorizeService.isAdmin(context, item)).thenReturn(true);
        DatashareDataset dataset = mock(DatashareDataset.class);
        lenient().when(dataset.getChecksum()).thenReturn(checksum);
        when(datashareDatasetDAO.findLatestDatashareDatasetByItem(context, item)).thenReturn(dataset);
        try (MockedStatic<DatashareItemDataset> mocked = mockStatic(DatashareItemDataset.class)) {
            mocked.when(() -> DatashareItemDataset.areAllItemBitstreamsAvailable(context, item)).thenReturn(true);
            mocked.when(() -> DatashareItemDataset.getFullFilePath("123456789/42"))
                    .thenReturn(zipFile.getAbsolutePath());
            mocked.when(() -> DatashareItemDataset.getURL(item)).thenReturn(baseUrl);
            return datashareDatasetService.fetchDatashareDatasetZipFileLink(context, item);
        }
    }

    @Test
    public void fetchLinkAppendsChecksumAsCacheBustingVersion() throws Exception {
        File zip = File.createTempFile("DS_cachebust", ".zip");
        zip.deleteOnExit();
        try {
            String link = fetchLinkWith("http://localhost:8080/download/DS_123456789_42.zip",
                    "abc123checksum", zip);
            assertEquals("http://localhost:8080/download/DS_123456789_42.zip?v=abc123checksum", link);
        } finally {
            zip.delete();
        }
    }

    @Test
    public void fetchLinkVersionChangesWhenZipContentChanges() throws Exception {
        // Core of the bug: the link must change when the zip content (checksum) changes.
        File zip = File.createTempFile("DS_cachebust", ".zip");
        zip.deleteOnExit();
        try {
            String before = fetchLinkWith("http://localhost:8080/download/DS_123456789_42.zip",
                    "checksum-before", zip);
            String after = fetchLinkWith("http://localhost:8080/download/DS_123456789_42.zip",
                    "checksum-after", zip);
            assertNotEquals("regenerating the zip must change the download link so the browser refetches",
                    before, after);
            assertTrue("stale link must carry the old version: " + before, before.endsWith("?v=checksum-before"));
            assertTrue("fresh link must carry the new version: " + after, after.endsWith("?v=checksum-after"));
        } finally {
            zip.delete();
        }
    }

    @Test
    public void fetchLinkFallsBackToLastModifiedWhenChecksumMissing() throws Exception {
        // Legacy dataset rows (DSpace 6->8 migration) can have a null/blank checksum; the link must
        // still carry a version token, so fall back to the physical file's last-modified time.
        File zip = File.createTempFile("DS_cachebust", ".zip");
        zip.deleteOnExit();
        try {
            String link = fetchLinkWith("http://localhost:8080/download/DS_123456789_42.zip", null, zip);
            assertEquals("http://localhost:8080/download/DS_123456789_42.zip?v=" + zip.lastModified(), link);
        } finally {
            zip.delete();
        }
    }
}

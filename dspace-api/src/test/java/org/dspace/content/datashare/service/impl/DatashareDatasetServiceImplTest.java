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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.datashare.dao.DatashareDatasetDAO;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for the authorization logic that protects the Datashare "download all files"
 * (zip) feature. See https://github.com/dataquest-dev/dspace-customers/issues/647.
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
    private DatashareDatasetDAO datashareDatasetDAO;

    @Mock
    private Context context;

    @Test
    public void authorizedWhenAllBitstreamsAreReadable() throws Exception {
        Item item = mock(Item.class);
        Bitstream b1 = mock(Bitstream.class);
        Bitstream b2 = mock(Bitstream.class);
        Bundle original = mock(Bundle.class);

        when(itemService.getBundles(item, "ORIGINAL")).thenReturn(List.of(original));
        when(itemService.getBundles(item, "CC-LICENSE")).thenReturn(Collections.emptyList());
        when(itemService.getBundles(item, "LICENSE")).thenReturn(Collections.emptyList());
        when(original.getBitstreams()).thenReturn(List.of(b1, b2));
        when(authorizeService.authorizeActionBoolean(context, b1, Constants.READ)).thenReturn(true);
        when(authorizeService.authorizeActionBoolean(context, b2, Constants.READ)).thenReturn(true);

        assertTrue(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
    }

    @Test
    public void forbiddenWhenAnyBitstreamIsNotReadable() throws Exception {
        Item item = mock(Item.class);
        Bitstream readable = mock(Bitstream.class);
        Bitstream restricted = mock(Bitstream.class);
        Bundle original = mock(Bundle.class);

        when(itemService.getBundles(item, "ORIGINAL")).thenReturn(List.of(original));
        when(original.getBitstreams()).thenReturn(List.of(readable, restricted));
        when(authorizeService.authorizeActionBoolean(context, readable, Constants.READ)).thenReturn(true);
        when(authorizeService.authorizeActionBoolean(context, restricted, Constants.READ)).thenReturn(false);

        assertFalse(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
    }

    @Test
    public void forbiddenWhenAllBitstreamsAreRestricted() throws Exception {
        Item item = mock(Item.class);
        Bitstream restricted = mock(Bitstream.class);
        Bundle original = mock(Bundle.class);

        when(itemService.getBundles(item, "ORIGINAL")).thenReturn(List.of(original));
        when(original.getBitstreams()).thenReturn(List.of(restricted));
        when(authorizeService.authorizeActionBoolean(context, restricted, Constants.READ)).thenReturn(false);

        assertFalse(datashareDatasetService.isUserAuthorizedToDownloadZip(context, item));
    }

    @Test
    public void forbiddenWhenItemIsNull() throws Exception {
        assertFalse(datashareDatasetService.isUserAuthorizedToDownloadZip(context, null));
    }

    @Test
    public void notDownloadableWhenUserIsNotAuthorized() throws Exception {
        Item item = mock(Item.class);
        Bitstream restricted = mock(Bitstream.class);
        Bundle original = mock(Bundle.class);

        when(itemService.getBundles(item, "ORIGINAL")).thenReturn(List.of(original));
        when(original.getBitstreams()).thenReturn(List.of(restricted));
        when(authorizeService.authorizeActionBoolean(context, restricted, Constants.READ)).thenReturn(false);

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

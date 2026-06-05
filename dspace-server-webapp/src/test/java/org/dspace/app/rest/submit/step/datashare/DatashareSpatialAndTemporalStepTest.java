/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit.step.datashare;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Collections;

import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the encode/decode time period logic in
 * {@link DatashareSpatialAndTemporalStep}.
 */
public class DatashareSpatialAndTemporalStepTest {

    // ---- encodeTimePeriod tests ----

    @Test
    public void testEncodeFullDates() {
        String result = DatashareSpatialAndTemporalStep.encodeTimePeriod("2019-02-21", "2020-03-18");
        assertEquals("start=2019-02-21; end=2020-03-18; scheme=W3C-DTF", result);
    }

    @Test
    public void testEncodeYearOnly() {
        String result = DatashareSpatialAndTemporalStep.encodeTimePeriod("2021", "2025");
        assertEquals("start=2021; end=2025; scheme=W3C-DTF", result);
    }

    @Test
    public void testEncodeYearMonth() {
        String result = DatashareSpatialAndTemporalStep.encodeTimePeriod("2021-03", "2022-11");
        assertEquals("start=2021-03; end=2022-11; scheme=W3C-DTF", result);
    }

    @Test
    public void testEncodeNullStart() {
        String result = DatashareSpatialAndTemporalStep.encodeTimePeriod(null, "2025");
        assertEquals("start=; end=2025; scheme=W3C-DTF", result);
    }

    @Test
    public void testEncodeNullEnd() {
        String result = DatashareSpatialAndTemporalStep.encodeTimePeriod("2021", null);
        assertEquals("start=2021; end=; scheme=W3C-DTF", result);
    }

    // ---- decodeTimePeriod tests ----

    @Test
    public void testDecodeFullDates() {
        String[] result = DatashareSpatialAndTemporalStep.decodeTimePeriod(
                "start=2019-02-21; end=2020-03-18; scheme=W3C-DTF");
        assertArrayEquals(new String[]{"2019-02-21", "2020-03-18"}, result);
    }

    @Test
    public void testDecodeYearOnly() {
        String[] result = DatashareSpatialAndTemporalStep.decodeTimePeriod(
                "start=2021; end=2025; scheme=W3C-DTF");
        assertArrayEquals(new String[]{"2021", "2025"}, result);
    }

    @Test
    public void testDecodeYearMonth() {
        String[] result = DatashareSpatialAndTemporalStep.decodeTimePeriod(
                "start=2021-03; end=2022-11; scheme=W3C-DTF");
        assertArrayEquals(new String[]{"2021-03", "2022-11"}, result);
    }

    @Test
    public void testDecodeNull() {
        String[] result = DatashareSpatialAndTemporalStep.decodeTimePeriod(null);
        assertNull(result);
    }

    @Test
    public void testDecodeEmpty() {
        String[] result = DatashareSpatialAndTemporalStep.decodeTimePeriod("");
        assertNull(result);
    }

    @Test
    public void testDecodeEmptyDates() {
        String[] result = DatashareSpatialAndTemporalStep.decodeTimePeriod(
                "start=; end=; scheme=W3C-DTF");
        assertArrayEquals(new String[]{"", ""}, result);
    }

    @Test
    public void testRoundTrip() {
        String encoded = DatashareSpatialAndTemporalStep.encodeTimePeriod("2021-03-15", "2023-12-31");
        String[] decoded = DatashareSpatialAndTemporalStep.decodeTimePeriod(encoded);
        assertArrayEquals(new String[]{"2021-03-15", "2023-12-31"}, decoded);
    }

    // ---- syncTemporalCoverage tests ----

    /**
     * Regression test for uoe/temporal-metadata-issue.
     *
     * When both start and end dates are present, dc.coverage.temporal must be (re)encoded for the
     * export crosswalks, but the individual dc.coverage.startDate / dc.coverage.endDate fields must
     * NOT be cleared. They used to be cleared, which made the entered dates disappear from the
     * submission form on reload (the form is populated from the item's stored metadata).
     */
    @Test
    public void syncTemporalCoverageRetainsIndividualDateFields() throws Exception {
        ItemService itemService = Mockito.mock(ItemService.class);
        Context context = Mockito.mock(Context.class);
        Item item = Mockito.mock(Item.class);
        MetadataValue start = Mockito.mock(MetadataValue.class);
        MetadataValue end = Mockito.mock(MetadataValue.class);
        Mockito.when(start.getValue()).thenReturn("2021-04-15");
        Mockito.when(end.getValue()).thenReturn("2023-09-20");
        Mockito.when(itemService.getMetadataByMetadataString(item, "dc.coverage.startDate"))
            .thenReturn(Collections.singletonList(start));
        Mockito.when(itemService.getMetadataByMetadataString(item, "dc.coverage.endDate"))
            .thenReturn(Collections.singletonList(end));

        DatashareSpatialAndTemporalStep.syncTemporalCoverage(itemService, context, item);

        // The canonical temporal value is (re)encoded for export.
        Mockito.verify(itemService).addMetadata(context, item, "dc", "coverage", "temporal", null,
            "start=2021-04-15; end=2023-09-20; scheme=W3C-DTF");
        // The individual date fields MUST NOT be cleared.
        Mockito.verify(itemService, Mockito.never())
            .clearMetadata(context, item, "dc", "coverage", "startDate", Item.ANY);
        Mockito.verify(itemService, Mockito.never())
            .clearMetadata(context, item, "dc", "coverage", "endDate", Item.ANY);
    }

    /**
     * When the start/end pair is incomplete, any stale dc.coverage.temporal encoding must be removed
     * and no new temporal value should be encoded.
     */
    @Test
    public void syncTemporalCoverageClearsTemporalWhenPairIncomplete() throws Exception {
        ItemService itemService = Mockito.mock(ItemService.class);
        Context context = Mockito.mock(Context.class);
        Item item = Mockito.mock(Item.class);
        MetadataValue start = Mockito.mock(MetadataValue.class);
        Mockito.when(start.getValue()).thenReturn("2021-04-15");
        Mockito.when(itemService.getMetadataByMetadataString(item, "dc.coverage.startDate"))
            .thenReturn(Collections.singletonList(start));
        Mockito.when(itemService.getMetadataByMetadataString(item, "dc.coverage.endDate"))
            .thenReturn(Collections.emptyList());

        DatashareSpatialAndTemporalStep.syncTemporalCoverage(itemService, context, item);

        Mockito.verify(itemService).clearMetadata(context, item, "dc", "coverage", "temporal", Item.ANY);
        Mockito.verify(itemService, Mockito.never())
            .addMetadata(Mockito.any(Context.class), Mockito.any(Item.class), Mockito.eq("dc"),
                Mockito.eq("coverage"), Mockito.eq("temporal"), Mockito.nullable(String.class),
                Mockito.anyString());
    }
}

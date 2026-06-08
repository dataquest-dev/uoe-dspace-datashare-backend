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

import org.junit.Test;

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
}

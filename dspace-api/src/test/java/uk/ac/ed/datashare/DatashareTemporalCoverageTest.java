/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the pure encode/decode helpers in {@link DatashareTemporalCoverage}.
 */
public class DatashareTemporalCoverageTest {

    // ---- encodeTimePeriod ----

    @Test
    public void testEncodeFullDates() {
        assertEquals("start=2019-02-21; end=2020-03-18; scheme=W3C-DTF",
                DatashareTemporalCoverage.encodeTimePeriod("2019-02-21", "2020-03-18"));
    }

    @Test
    public void testEncodeYearOnly() {
        assertEquals("start=2021; end=2025; scheme=W3C-DTF",
                DatashareTemporalCoverage.encodeTimePeriod("2021", "2025"));
    }

    @Test
    public void testEncodeYearMonth() {
        assertEquals("start=2021-03; end=2022-11; scheme=W3C-DTF",
                DatashareTemporalCoverage.encodeTimePeriod("2021-03", "2022-11"));
    }

    @Test
    public void testEncodeNullStart() {
        assertEquals("start=; end=2025; scheme=W3C-DTF",
                DatashareTemporalCoverage.encodeTimePeriod(null, "2025"));
    }

    @Test
    public void testEncodeNullEnd() {
        assertEquals("start=2021; end=; scheme=W3C-DTF",
                DatashareTemporalCoverage.encodeTimePeriod("2021", null));
    }

    // ---- decodeTimePeriod ----

    @Test
    public void testDecodeFullDates() {
        assertArrayEquals(new String[]{"2019-02-21", "2020-03-18"},
                DatashareTemporalCoverage.decodeTimePeriod("start=2019-02-21; end=2020-03-18; scheme=W3C-DTF"));
    }

    @Test
    public void testDecodeYearOnly() {
        assertArrayEquals(new String[]{"2021", "2025"},
                DatashareTemporalCoverage.decodeTimePeriod("start=2021; end=2025; scheme=W3C-DTF"));
    }

    @Test
    public void testDecodeYearMonth() {
        assertArrayEquals(new String[]{"2021-03", "2022-11"},
                DatashareTemporalCoverage.decodeTimePeriod("start=2021-03; end=2022-11; scheme=W3C-DTF"));
    }

    @Test
    public void testDecodeNull() {
        assertNull(DatashareTemporalCoverage.decodeTimePeriod(null));
    }

    @Test
    public void testDecodeEmpty() {
        assertNull(DatashareTemporalCoverage.decodeTimePeriod(""));
    }

    @Test
    public void testDecodeEmptyDates() {
        assertArrayEquals(new String[]{"", ""},
                DatashareTemporalCoverage.decodeTimePeriod("start=; end=; scheme=W3C-DTF"));
    }

    @Test
    public void testRoundTrip() {
        String encoded = DatashareTemporalCoverage.encodeTimePeriod("2021-03-15", "2023-12-31");
        assertArrayEquals(new String[]{"2021-03-15", "2023-12-31"},
                DatashareTemporalCoverage.decodeTimePeriod(encoded));
    }
}

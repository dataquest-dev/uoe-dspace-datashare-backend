/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for the pure splitCountryAndPlace logic in {@link DatashareSpatialCoverage}.
 */
public class DatashareSpatialCoverageTest {

    private static final Set<String> COUNTRY_CODES = new HashSet<>(Arrays.asList("UK", "DZ", "AS", "FR"));

    @Test
    public void testSplitMixedCountryAndPlace() {
        List<List<String>> result = DatashareSpatialCoverage.splitCountryAndPlace(
                Arrays.asList("Edinburgh", "UK", "Bologna", "FR"), COUNTRY_CODES);
        assertEquals(Arrays.asList("UK", "FR"), result.get(0));
        assertEquals(Arrays.asList("Edinburgh", "Bologna"), result.get(1));
    }

    @Test
    public void testSplitOnlyPlaces() {
        List<List<String>> result = DatashareSpatialCoverage.splitCountryAndPlace(
                Arrays.asList("Edinburgh", "Bologna"), COUNTRY_CODES);
        assertEquals(Collections.emptyList(), result.get(0));
        assertEquals(Arrays.asList("Edinburgh", "Bologna"), result.get(1));
    }

    @Test
    public void testSplitOnlyCountries() {
        List<List<String>> result = DatashareSpatialCoverage.splitCountryAndPlace(
                Arrays.asList("UK", "DZ"), COUNTRY_CODES);
        assertEquals(Arrays.asList("UK", "DZ"), result.get(0));
        assertEquals(Collections.emptyList(), result.get(1));
    }

    @Test
    public void testSplitPreservesOrder() {
        List<List<String>> result = DatashareSpatialCoverage.splitCountryAndPlace(
                Arrays.asList("FR", "Paris", "UK", "Glasgow", "DZ"), COUNTRY_CODES);
        assertEquals(Arrays.asList("FR", "UK", "DZ"), result.get(0));
        assertEquals(Arrays.asList("Paris", "Glasgow"), result.get(1));
    }

    @Test
    public void testSplitNullList() {
        List<List<String>> result = DatashareSpatialCoverage.splitCountryAndPlace(null, COUNTRY_CODES);
        assertEquals(Collections.emptyList(), result.get(0));
        assertEquals(Collections.emptyList(), result.get(1));
    }

    @Test
    public void testSplitEmptyCountryCodesTreatsAllAsPlaces() {
        List<List<String>> result = DatashareSpatialCoverage.splitCountryAndPlace(
                Arrays.asList("UK", "Edinburgh"), new HashSet<>());
        assertEquals(Collections.emptyList(), result.get(0));
        assertEquals(Arrays.asList("UK", "Edinburgh"), result.get(1));
    }

    @Test
    public void testSplitSkipsNullAndBlankValues() {
        List<List<String>> result = DatashareSpatialCoverage.splitCountryAndPlace(
                Arrays.asList("Edinburgh", "  ", null, "UK", ""), COUNTRY_CODES);
        assertEquals(Arrays.asList("UK"), result.get(0));
        assertEquals(Arrays.asList("Edinburgh"), result.get(1));
    }
}

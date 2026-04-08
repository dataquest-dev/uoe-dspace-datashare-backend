/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import org.junit.Test;

public class DatashareItemDatasetTest {

    @Test
    public void testParseDateValidYMD() {
        String s = "2015-05-07";
        Date d = DatashareItemDataset.parseDate(s);
        assertNotNull(d);
        LocalDate ld = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        assertEquals(LocalDate.of(2015, 5, 7), ld);
    }

    @Test
    public void testParseDateInvalidFormatReturnsNull() {
        String s = "05/07/2015";
        Date d = DatashareItemDataset.parseDate(s);
        assertNull(d);
    }

    @Test
    public void testParseDateNullReturnsNull() {
        assertNull(DatashareItemDataset.parseDate(null));
    }
}

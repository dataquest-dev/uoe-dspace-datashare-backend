/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import java.sql.SQLException;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;

/**
 * Datashare temporal-coverage helper.
 *
 * <p>The "Temporal Coverage" start/end date pickers are bound to {@code dc.coverage.startDate} and
 * {@code dc.coverage.endDate} (transient, form-only fields) while a record is being edited, so that
 * the date pickers re-populate from the item's stored metadata on every reload (in submission and in
 * the workflow editor). The DSpace submission form is rendered from stored metadata, not from a
 * step's transformed section data, so the dates must physically live in their own fields during
 * editing.</p>
 *
 * <p>Production (datashare.ed.ac.uk) holds the temporal coverage as a single encoded
 * {@code dc.coverage.temporal} value (W3C-DTF profile, e.g. {@code start=2021; end=2023;
 * scheme=W3C-DTF}) and the QDC/MODS/OAI/DataCite crosswalks read it from there. To match that, the
 * start/end fields are encoded into {@code dc.coverage.temporal} (and the individual fields cleared)
 * when the item is <strong>archived</strong>
 * ({@link uk.ac.ed.datashare.event.DatashareTemporalCoverageConsumer}). The reverse decode is applied
 * when a new version of an archived item is created
 * ({@link uk.ac.ed.datashare.DatashareItemVersionProvider}) so its date pickers work again.</p>
 */
public final class DatashareTemporalCoverage {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareTemporalCoverage.class);

    private static final String ENCODING_SCHEME = "W3C-DTF";

    private DatashareTemporalCoverage() {
    }

    /**
     * Archive transform: encode {@code dc.coverage.startDate} / {@code dc.coverage.endDate} into the
     * canonical {@code dc.coverage.temporal} value and clear the individual date fields. No-op if
     * neither individual date field is present. Blank date values are treated as absent, so no
     * meaningless temporal value is encoded when both dates are blank.
     */
    public static void mergeDatesIntoTemporal(Context context, Item item, ItemService itemService)
            throws SQLException, AuthorizeException {
        List<MetadataValue> startDates = itemService.getMetadataByMetadataString(item, "dc.coverage.startDate");
        List<MetadataValue> endDates = itemService.getMetadataByMetadataString(item, "dc.coverage.endDate");
        if (startDates.isEmpty() && endDates.isEmpty()) {
            return;
        }
        String startValue = startDates.isEmpty() ? null : startDates.get(0).getValue();
        String endValue = endDates.isEmpty() ? null : endDates.get(0).getValue();

        // Treat blank values as absent: a cleared form field can leave an empty metadata value behind,
        // which must not be encoded into a meaningless dc.coverage.temporal (it would reach OAI/DataCite).
        boolean hasStart = StringUtils.isNotBlank(startValue);
        boolean hasEnd = StringUtils.isNotBlank(endValue);

        itemService.clearMetadata(context, item, "dc", "coverage", "temporal", Item.ANY);
        if (hasStart || hasEnd) {
            String encoded = encodeTimePeriod(hasStart ? startValue : null, hasEnd ? endValue : null);
            itemService.addMetadata(context, item, "dc", "coverage", "temporal", null, encoded);
            log.info("Datashare: encoded start/end into dc.coverage.temporal on archive for item {}", item.getID());
        } else {
            log.info("Datashare: blank start/end on archive for item {}; no temporal encoded", item.getID());
        }
        itemService.clearMetadata(context, item, "dc", "coverage", "startDate", Item.ANY);
        itemService.clearMetadata(context, item, "dc", "coverage", "endDate", Item.ANY);
        itemService.update(context, item);
    }

    /**
     * Reverse transform (e.g. when a new version of an archived item is created): decode
     * {@code dc.coverage.temporal} back into {@code dc.coverage.startDate} / {@code dc.coverage.endDate}
     * (re-populating the date pickers) and remove the encoded value. No-op if there is no temporal
     * value, or if the individual date fields are already present.
     */
    public static void splitTemporalIntoDates(Context context, Item item, ItemService itemService)
            throws SQLException, AuthorizeException {
        List<MetadataValue> temporalValues = itemService.getMetadataByMetadataString(item, "dc.coverage.temporal");
        if (temporalValues.isEmpty()) {
            return;
        }
        boolean hasStartDate = !itemService.getMetadataByMetadataString(item, "dc.coverage.startDate").isEmpty();
        boolean hasEndDate = !itemService.getMetadataByMetadataString(item, "dc.coverage.endDate").isEmpty();
        if (hasStartDate || hasEndDate) {
            return;
        }
        String[] decoded = decodeTimePeriod(temporalValues.get(0).getValue());
        if (decoded == null) {
            return;
        }
        itemService.clearMetadata(context, item, "dc", "coverage", "temporal", Item.ANY);
        if (StringUtils.isNotBlank(decoded[0])) {
            itemService.addMetadata(context, item, "dc", "coverage", "startDate", null, decoded[0]);
        }
        if (StringUtils.isNotBlank(decoded[1])) {
            itemService.addMetadata(context, item, "dc", "coverage", "endDate", null, decoded[1]);
        }
        itemService.update(context, item);
        log.info("Datashare: decoded dc.coverage.temporal back into start/end for item {}", item.getID());
    }

    /**
     * Encode a start and end date into the W3C-DTF profile of ISO 8601, e.g.
     * {@code start=2021; end=2023; scheme=W3C-DTF}. A null date is encoded as empty. Pure helper.
     */
    public static String encodeTimePeriod(String start, String end) {
        String startStr = start == null ? "" : start;
        String endStr = end == null ? "" : end;
        return "start=" + startStr + "; end=" + endStr + "; scheme=" + ENCODING_SCHEME;
    }

    /**
     * Decode a W3C-DTF encoded time period string into its start and end date components.
     * Pure helper.
     *
     * @param temporal encoded string, e.g. {@code start=2021; end=2023; scheme=W3C-DTF}.
     * @return a {@code [startDate, endDate]} array, or {@code null} if the input is null/empty.
     */
    public static String[] decodeTimePeriod(String temporal) {
        if (StringUtils.isEmpty(temporal)) {
            return null;
        }
        String startDate = null;
        String endDate = null;
        for (String token : temporal.split(";")) {
            String trimmed = token.trim();
            if (trimmed.startsWith("start=")) {
                startDate = trimmed.substring("start=".length());
            } else if (trimmed.startsWith("end=")) {
                endDate = trimmed.substring("end=".length());
            }
        }
        return new String[]{startDate, endDate};
    }
}

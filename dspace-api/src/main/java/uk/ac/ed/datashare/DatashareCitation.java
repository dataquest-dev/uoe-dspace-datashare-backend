/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import java.sql.SQLException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;

/**
 * Canonical DataShare citation logic (schema {@code dc.identifier.citation}).
 *
 * <p>This is the single source of truth for building and maintaining the human-readable citation
 * that DataShare shows in the item view. It is used from two places:</p>
 * <ul>
 *   <li>{@code DatashareDoiCitationConsumer} &mdash; event driven: writes a citation (with a DOI
 *       placeholder) at {@code Item+Install}, and re-writes it to embed the real DOI when the
 *       {@code doi-organiser} job registers the DOI and stores it in {@code dc.identifier.uri}
 *       (which fires {@code Item+Modify_Metadata}).</li>
 *   <li>{@code uk.ac.ed.datashare.commands.DatashareDoiCitationUpdaterCLI} &mdash; the legacy
 *       {@code ds-doi-citation} batch, kept as a backfill / safety net.</li>
 * </ul>
 *
 * <p>The DOI itself is <strong>not</strong> minted or registered here &mdash; that stays with the
 * asynchronous DOI queue drained by {@code doi-organiser}. This class only reflects the DOI that
 * the queue has already written into {@code dc.identifier.uri} into the citation string.</p>
 */
public final class DatashareCitation {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareCitation.class);

    /** Resolver-URL prefix of a registered DOI as stored by the DOIIdentifierProvider. */
    public static final String DOI_URL = "https://doi.org";

    private static final String MD_SCHEMA = "dc";
    private static final String IDENTIFIER_ELEMENT = "identifier";
    private static final String URI_QUALIFIER = "uri";
    private static final String CITATION_QUALIFIER = "citation";
    private static final String CITATION_LANG = "en";

    private DatashareCitation() {
    }

    /**
     * The registered DOI resolver URL of an item, if any.
     *
     * @param item        the item
     * @param itemService the item service
     * @return the {@code https://doi.org/...} value from {@code dc.identifier.uri}, or {@code null}
     *         if the item has no registered DOI yet.
     */
    public static String getRegisteredDoiUrl(Item item, ItemService itemService) {
        List<MetadataValue> identifiers =
                itemService.getMetadata(item, MD_SCHEMA, IDENTIFIER_ELEMENT, URI_QUALIFIER, Item.ANY, false);
        for (MetadataValue identifier : identifiers) {
            String value = identifier.getValue();
            if (value != null && value.startsWith(DOI_URL)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Whether the item already has a registered DOI in {@code dc.identifier.uri}.
     *
     * @param item        the item
     * @param itemService the item service
     * @return {@code true} if a {@code https://doi.org/...} value is present.
     */
    public static boolean hasDoi(Item item, ItemService itemService) {
        return getRegisteredDoiUrl(item, itemService) != null;
    }

    /**
     * The current {@code dc.identifier.citation} value, or {@code null} if not set.
     *
     * @param item        the item
     * @param itemService the item service
     * @return the first citation value or {@code null}.
     */
    public static String getCitation(Item item, ItemService itemService) {
        List<MetadataValue> citations =
                itemService.getMetadata(item, MD_SCHEMA, IDENTIFIER_ELEMENT, CITATION_QUALIFIER, Item.ANY, false);
        return citations.isEmpty() ? null : citations.get(0).getValue();
    }

    /**
     * Whether the citation needs (re)writing:
     * <ul>
     *   <li>no citation exists yet, or</li>
     *   <li>the item now has a registered DOI but the existing citation does not contain it (i.e. the
     *       citation still holds the pre-registration placeholder).</li>
     * </ul>
     *
     * @param item        the item
     * @param itemService the item service
     * @return {@code true} if {@link #applyCitation} would change something.
     */
    public static boolean needsCitationUpdate(Item item, ItemService itemService) {
        String citation = getCitation(item, itemService);
        boolean needsNewCitation = citation == null;
        boolean needsDoiFoldedIn = hasDoi(item, itemService) && citation != null && !citation.contains(DOI_URL);
        return needsNewCitation || needsDoiFoldedIn;
    }

    /**
     * (Re)write {@code dc.identifier.citation} if needed. Idempotent: if the citation is already
     * up to date this is a no-op, which is what stops the {@code Modify_Metadata} event that a write
     * fires from causing an update loop.
     *
     * @param context     the DSpace context
     * @param item        the item
     * @param itemService the item service
     * @param placeholder text used in place of the DOI while the DOI is not registered yet
     *                    (may be empty for "no placeholder")
     * @throws SQLException       on database error
     * @throws AuthorizeException on authorisation error
     */
    public static void applyCitation(Context context, Item item, ItemService itemService, String placeholder)
            throws SQLException, AuthorizeException {
        if (!needsCitationUpdate(item, itemService)) {
            return;
        }
        String newCitation = buildCitation(item, itemService, placeholder);
        if (newCitation == null) {
            return;
        }
        // Replace any existing citation so we never leave a stale placeholder behind.
        itemService.clearMetadata(context, item, MD_SCHEMA, IDENTIFIER_ELEMENT, CITATION_QUALIFIER, Item.ANY);
        itemService.addMetadata(context, item, MD_SCHEMA, IDENTIFIER_ELEMENT, CITATION_QUALIFIER,
                CITATION_LANG, newCitation);
        itemService.update(context, item);
        log.info("DataShare: citation set for item {} (hasDoi={})", item.getID(), hasDoi(item, itemService));
    }

    /**
     * Build the citation string for an item. If the item has a registered DOI it is appended;
     * otherwise the supplied {@code placeholder} is appended in its place (so the citation field is
     * visible in the item view immediately after deposit, before {@code doi-organiser} runs).
     *
     * @param item        the item
     * @param itemService the item service
     * @param placeholder text used in place of the DOI while the DOI is not registered yet
     *                    (may be {@code null} or empty for "no placeholder")
     * @return the citation string, or {@code null} on error.
     */
    public static String buildCitation(Item item, ItemService itemService, String placeholder) {
        try {
            StringBuilder buffer = new StringBuilder(200);

            // Creators (fall back to publisher if none)
            List<MetadataValue> creators =
                    itemService.getMetadata(item, MD_SCHEMA, "creator", Item.ANY, Item.ANY, false);
            boolean creatorGiven = !creators.isEmpty();

            if (creatorGiven) {
                for (int i = 0; i < creators.size(); i++) {
                    if (i > 0) {
                        buffer.append("; ");
                    }
                    buffer.append(creators.get(i).getValue());
                }
                buffer.append(". ");
            } else {
                List<MetadataValue> publishers =
                        itemService.getMetadata(item, MD_SCHEMA, "publisher", Item.ANY, Item.ANY, false);
                if (!publishers.isEmpty()) {
                    buffer.append(" ");
                    buffer.append(publishers.get(0).getValue());
                    buffer.append(".");
                }
                buffer.append(" ");
            }

            // Year from dc.date.available (fall back to current year)
            buffer.append("(");
            List<MetadataValue> dateAvailable =
                    itemService.getMetadata(item, MD_SCHEMA, "date", "available", Item.ANY, false);
            if (!dateAvailable.isEmpty()) {
                String dateStr = dateAvailable.get(0).getValue();
                String year = dateStr.length() >= 4 ? dateStr.substring(0, 4) : dateStr;
                buffer.append(year);
            } else {
                Calendar calendar = new GregorianCalendar();
                calendar.setTime(new Date());
                buffer.append(calendar.get(Calendar.YEAR));
            }
            buffer.append("). ");

            // Title
            List<MetadataValue> titles =
                    itemService.getMetadata(item, MD_SCHEMA, "title", Item.ANY, Item.ANY, false);
            if (!titles.isEmpty()) {
                buffer.append(titles.get(0).getValue());
            }
            buffer.append(", ");

            // Time period from dc.coverage.temporal (reuse the tested decoder in DatashareTemporalCoverage)
            List<MetadataValue> temporal =
                    itemService.getMetadata(item, MD_SCHEMA, "coverage", "temporal", Item.ANY, false);
            if (!temporal.isEmpty()) {
                String[] dates = DatashareTemporalCoverage.decodeTimePeriod(temporal.get(0).getValue());

                if (dates != null && dates[0] != null && !dates[0].isEmpty()
                        && dates[1] != null && !dates[1].isEmpty()) {
                    String from = dates[0].length() >= 4 ? dates[0].substring(0, 4) : dates[0];
                    String to = dates[1].length() >= 4 ? dates[1].substring(0, 4) : dates[1];
                    String timePeriod = from.equals(to) ? from : from + "-" + to;
                    buffer.append(timePeriod);
                    buffer.append(" ");
                }
            }

            // Item type
            List<MetadataValue> types =
                    itemService.getMetadata(item, MD_SCHEMA, "type", Item.ANY, Item.ANY, false);
            buffer.append("[");
            if (!types.isEmpty()) {
                buffer.append(types.get(0).getValue());
            }
            buffer.append("].");

            // Publisher again if creators were given
            if (creatorGiven) {
                List<MetadataValue> publishers =
                        itemService.getMetadata(item, MD_SCHEMA, "publisher", Item.ANY, Item.ANY, false);
                if (!publishers.isEmpty()) {
                    buffer.append(" ");
                    buffer.append(publishers.get(0).getValue());
                    buffer.append(".");
                }
            }

            // DOI when registered, else the placeholder
            String doiUrl = getRegisteredDoiUrl(item, itemService);
            if (doiUrl != null) {
                buffer.append(" ");
                buffer.append(doiUrl);
                buffer.append(".");
            } else if (placeholder != null && !placeholder.isEmpty()) {
                buffer.append(" ");
                buffer.append(placeholder);
            }

            return buffer.toString();
        } catch (Exception e) {
            log.error("Error creating citation for item {}: {}", item.getID(), e.getMessage());
            return null;
        }
    }
}

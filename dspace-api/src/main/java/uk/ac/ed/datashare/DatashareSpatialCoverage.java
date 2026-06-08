/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.app.util.DCInput;
import org.dspace.app.util.DCInputSet;
import org.dspace.app.util.DCInputsReader;
import org.dspace.app.util.DCInputsReaderException;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;

/**
 * Datashare spatial-coverage helper.
 *
 * <p>The "Spatial Coverage: Country" dropdown is bound to {@code dc.subject.ddc} (a transient,
 * form-only field) while a record is being edited, so that the value-pairs dropdown re-populates from
 * the item's stored metadata on every reload (in submission and in the workflow editor). The DSpace
 * submission form is rendered from stored metadata, not from a step's transformed section data, so the
 * country must physically live in its own field during editing.</p>
 *
 * <p>Production (datashare.ed.ac.uk) holds country <em>and</em> place together in
 * {@code dc.coverage.spatial} and never uses {@code dc.subject.ddc}. To match that, the country is
 * merged into {@code dc.coverage.spatial} (and {@code dc.subject.ddc} cleared) when the item is
 * <strong>archived</strong> ({@link uk.ac.ed.datashare.event.DatashareSpatialCoverageConsumer}). The
 * reverse split is applied when a new version of an archived item is created
 * ({@link uk.ac.ed.datashare.DatashareItemVersionProvider}) so its dropdown works again.</p>
 */
public final class DatashareSpatialCoverage {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareSpatialCoverage.class);

    /** Submission form that contains the spatial fields (used to read the country value-pairs). */
    public static final String SPATIAL_FORM_NAME = "datashareSpatialAndTemporalForm";

    private DatashareSpatialCoverage() {
    }

    /**
     * Archive transform: move every {@code dc.subject.ddc} value into {@code dc.coverage.spatial}
     * (skipping values already present there) and clear {@code dc.subject.ddc}. No-op if there is no
     * country to move.
     */
    public static void mergeCountryIntoSpatial(Context context, Item item, ItemService itemService)
            throws SQLException, AuthorizeException {
        List<MetadataValue> countries = itemService.getMetadataByMetadataString(item, "dc.subject.ddc");
        if (countries.isEmpty()) {
            return;
        }
        Set<String> existingSpatial = new HashSet<>();
        for (MetadataValue mv : itemService.getMetadataByMetadataString(item, "dc.coverage.spatial")) {
            existingSpatial.add(mv.getValue());
        }
        for (MetadataValue country : countries) {
            String value = country.getValue();
            if (StringUtils.isBlank(value) || existingSpatial.contains(value)) {
                continue;
            }
            itemService.addMetadata(context, item, "dc", "coverage", "spatial", null, value);
            existingSpatial.add(value);
        }
        itemService.clearMetadata(context, item, "dc", "subject", "ddc", Item.ANY);
        itemService.update(context, item);
        log.info("Datashare: merged dc.subject.ddc into dc.coverage.spatial on archive for item {}", item.getID());
    }

    /**
     * Reverse transform (e.g. when a new version of an archived item is created): split
     * {@code dc.coverage.spatial} so the values that match a known country code move to
     * {@code dc.subject.ddc} (re-populating the dropdown) while free-text places stay put. No-op if
     * there are no country codes to move.
     */
    public static void splitSpatialIntoCountry(Context context, Item item, ItemService itemService,
            Set<String> countryCodes) throws SQLException, AuthorizeException {
        if (countryCodes == null || countryCodes.isEmpty()) {
            return;
        }
        List<String> spatialValues = new ArrayList<>();
        for (MetadataValue mv : itemService.getMetadataByMetadataString(item, "dc.coverage.spatial")) {
            spatialValues.add(mv.getValue());
        }
        List<List<String>> split = splitCountryAndPlace(spatialValues, countryCodes);
        List<String> countries = split.get(0);
        List<String> places = split.get(1);
        if (countries.isEmpty()) {
            return;
        }
        Set<String> existingDdc = new HashSet<>();
        for (MetadataValue mv : itemService.getMetadataByMetadataString(item, "dc.subject.ddc")) {
            existingDdc.add(mv.getValue());
        }
        itemService.clearMetadata(context, item, "dc", "coverage", "spatial", Item.ANY);
        for (String place : places) {
            itemService.addMetadata(context, item, "dc", "coverage", "spatial", null, place);
        }
        for (String country : countries) {
            if (!existingDdc.contains(country)) {
                itemService.addMetadata(context, item, "dc", "subject", "ddc", null, country);
            }
        }
        itemService.update(context, item);
        log.info("Datashare: split dc.coverage.spatial back into dc.subject.ddc for item {}", item.getID());
    }

    /**
     * Partition dc.coverage.spatial string values into [countries, places]: values matching a known
     * country code go to countries, the rest to places (input order preserved). Pure helper.
     */
    public static List<List<String>> splitCountryAndPlace(List<String> spatialValues, Set<String> countryCodes) {
        List<String> countries = new ArrayList<>();
        List<String> places = new ArrayList<>();
        if (spatialValues != null) {
            for (String v : spatialValues) {
                if (StringUtils.isBlank(v)) {
                    continue;
                }
                if (countryCodes != null && countryCodes.contains(v)) {
                    countries.add(v);
                } else {
                    places.add(v);
                }
            }
        }
        List<List<String>> result = new ArrayList<>();
        result.add(countries);
        result.add(places);
        return result;
    }

    /**
     * Read the stored values (country codes) of the {@code dc.subject.ddc} dropdown's value-pairs from
     * the spatial submission form, so country values can be told apart from free-text places.
     */
    public static Set<String> getCountryCodes(DCInputsReader reader) {
        Set<String> codes = new HashSet<>();
        if (reader == null) {
            return codes;
        }
        try {
            DCInputSet inputSet = reader.getInputsByFormName(SPATIAL_FORM_NAME);
            for (DCInput[] row : inputSet.getFields()) {
                for (DCInput input : row) {
                    if ("dc.subject.ddc".equals(input.getFieldName())) {
                        List pairs = input.getPairs();
                        if (pairs != null) {
                            // value-pairs are stored as [displayed, stored, displayed, stored, ...]
                            for (int i = 1; i < pairs.size(); i += 2) {
                                Object stored = pairs.get(i);
                                if (stored != null && !stored.toString().isEmpty()) {
                                    codes.add(stored.toString());
                                }
                            }
                        }
                    }
                }
            }
        } catch (DCInputsReaderException e) {
            log.error("Could not read country value-pairs from form " + SPATIAL_FORM_NAME, e);
        }
        return codes;
    }
}

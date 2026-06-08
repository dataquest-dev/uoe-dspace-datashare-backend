/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare;

import java.util.Set;

import org.apache.logging.log4j.Logger;
import org.dspace.app.util.DCInputsReader;
import org.dspace.app.util.DCInputsReaderException;
import org.dspace.content.Item;
import org.dspace.core.Context;
import org.dspace.versioning.DefaultItemVersionProvider;

/**
 * When a new version of an archived item is created, its metadata is copied from the (archived)
 * previous version, where the spatial country lives in {@code dc.coverage.spatial} (see
 * {@link DatashareSpatialCoverage}) and the temporal coverage is encoded into
 * {@code dc.coverage.temporal} (see {@link DatashareTemporalCoverage}). The new version re-enters
 * submission/workflow editing, so we reverse both archive transforms: the country values are split
 * back out of {@code dc.coverage.spatial} into the {@code dc.subject.ddc} dropdown, and
 * {@code dc.coverage.temporal} is decoded back into the {@code dc.coverage.startDate} /
 * {@code dc.coverage.endDate} date fields, ensuring both the "Spatial Coverage: Country" dropdown and
 * the temporal date pickers re-populate while editing the new version.
 */
public class DatashareItemVersionProvider extends DefaultItemVersionProvider {

    private static final Logger log =
            org.apache.logging.log4j.LogManager.getLogger(DatashareItemVersionProvider.class);

    /** Cached: DCInputsReader re-parses submission-forms.xml in its constructor, so reuse one instance. */
    private DCInputsReader inputsReader;

    private DCInputsReader getInputsReader() throws DCInputsReaderException {
        if (inputsReader == null) {
            inputsReader = new DCInputsReader();
        }
        return inputsReader;
    }

    @Override
    public Item updateItemState(Context c, Item itemNew, Item previousItem) {
        Item result = super.updateItemState(c, itemNew, previousItem);
        try {
            Set<String> countryCodes = DatashareSpatialCoverage.getCountryCodes(getInputsReader());
            DatashareSpatialCoverage.splitSpatialIntoCountry(c, result, itemService, countryCodes);
        } catch (Exception e) {
            log.error("Datashare: failed to split spatial coverage into country for new version "
                    + (result != null ? result.getID() : null), e);
        }
        try {
            DatashareTemporalCoverage.splitTemporalIntoDates(c, result, itemService);
        } catch (Exception e) {
            log.error("Datashare: failed to split temporal coverage into start/end dates for new version "
                    + (result != null ? result.getID() : null), e);
        }
        return result;
    }
}

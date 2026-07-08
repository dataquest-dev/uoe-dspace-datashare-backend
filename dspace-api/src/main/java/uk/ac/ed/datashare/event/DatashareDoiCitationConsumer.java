/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare.event;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Logger;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.event.Consumer;
import org.dspace.event.Event;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import uk.ac.ed.datashare.DatashareCitation;

/**
 * Keeps {@code dc.identifier.citation} in step with the DOI lifecycle, so the citation field is
 * visible in the item view straight after deposit and gains the real DOI automatically once the
 * {@code doi-organiser} job registers it &mdash; with no second cron ({@code ds-doi-citation}) and
 * no synchronous DataCite call on the deposit thread.
 *
 * <p>Registered for {@code Item+Install} and {@code Item+Modify_Metadata}:</p>
 * <ul>
 *   <li><b>Install</b> &mdash; the DOI is queued ({@code TO_BE_REGISTERED}) but not yet registered,
 *       so {@link DatashareCitation#buildCitation} writes the citation with a placeholder in place of
 *       the DOI. The field therefore appears in the item view immediately, showing the placeholder.</li>
 *   <li><b>Modify_Metadata</b> &mdash; when {@code doi-organiser} registers the DOI it stores the
 *       {@code https://doi.org/...} value in {@code dc.identifier.uri}, which fires this event. The
 *       consumer then rewrites the citation to embed the real DOI, replacing the placeholder.</li>
 * </ul>
 *
 * <p>All work is done through {@link DatashareCitation#applyCitation}, which is idempotent: if the
 * citation is already correct it does nothing. That is what stops the {@code Modify_Metadata} event
 * fired by our own citation write from causing an update loop.</p>
 *
 * <p>The placeholder text is configurable via {@code datashare.doi.citation.placeholder}. An empty
 * value means "no placeholder" (the citation is simply written without a DOI until one is
 * registered).</p>
 */
public class DatashareDoiCitationConsumer implements Consumer {

    private static final Logger log =
            org.apache.logging.log4j.LogManager.getLogger(DatashareDoiCitationConsumer.class);

    /** Config key for the text shown in place of the DOI until the DOI is registered. */
    public static final String PLACEHOLDER_PROPERTY = "datashare.doi.citation.placeholder";

    private ItemService itemService;
    private String placeholder;

    /** Items touched during this event cycle, processed in {@link #end(Context)}. */
    private Set<UUID> itemsToProcess;

    @Override
    public void initialize() throws Exception {
        itemService = ContentServiceFactory.getInstance().getItemService();
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        // Default to empty ("no placeholder"); deployments set a human-readable value in config.
        placeholder = configurationService.getProperty(PLACEHOLDER_PROPERTY, "");
    }

    @Override
    public void consume(Context ctx, Event event) throws Exception {
        if (event.getSubjectType() != Constants.ITEM) {
            return;
        }
        if (event.getEventType() != Event.INSTALL && event.getEventType() != Event.MODIFY_METADATA) {
            return;
        }
        if (itemsToProcess == null) {
            // LinkedHashSet: O(1) de-duplication of repeated events for the same item, order preserved.
            itemsToProcess = new LinkedHashSet<>();
        }
        itemsToProcess.add(event.getSubjectID());
    }

    @Override
    public void end(Context ctx) throws Exception {
        if (itemsToProcess == null) {
            return;
        }
        try {
            ctx.turnOffAuthorisationSystem();
            for (UUID id : itemsToProcess) {
                Item item = itemService.find(ctx, id);
                // Only archived items get a citation; workspace/workflow items are ignored (the DOI is
                // minted at install, so there is nothing to reflect before then).
                if (item == null || !item.isArchived()) {
                    continue;
                }
                try {
                    DatashareCitation.applyCitation(ctx, item, itemService, placeholder);
                } catch (Exception e) {
                    log.error("DataShare citation update failed for item {}", id, e);
                }
            }
        } finally {
            ctx.restoreAuthSystemState();
            itemsToProcess = null;
        }
    }

    @Override
    public void finish(Context ctx) throws Exception {
        itemsToProcess = null;
    }
}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package uk.ac.ed.datashare.commands;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.DCDate;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.identifier.DOIIdentifierProvider;
import org.dspace.identifier.IdentifierException;
import org.dspace.identifier.IdentifierNotFoundException;
import org.dspace.identifier.IdentifierNotResolvableException;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.utils.DSpace;
import uk.ac.ed.datashare.DatashareCitation;

/**
 * Batch (backfill) tool to register DOIs for items that have none ({@code -d}) and to (re)build the
 * {@code dc.identifier.citation} value ({@code -c}). It shares its citation logic with
 * {@link uk.ac.ed.datashare.DatashareCitation}.
 *
 * <p>Since the event-driven {@link uk.ac.ed.datashare.event.DatashareDoiCitationConsumer} now writes
 * the citation at item install (with a DOI placeholder) and folds in the real DOI automatically when
 * the {@code doi-organiser} job registers it, this CLI is no longer required for day-to-day
 * operation. It is retained as a backfill / safety net (e.g. to (re)build citations for items that
 * pre-date the consumer). The batch keeps the historical "no placeholder" behaviour.</p>
 *
 * <p>Legacy cron (optional, backfill only):
 * {@code 5 8-19 * * * $DSPACE/bin/dspace ds-doi-citation -c > $DSPACE/log/doi-citation-updater.log 2>&1}</p>
 *
 * @author John Pinto
 */
public class DatashareDoiCitationUpdaterCLI {

    private static final Logger log = LogManager.getLogger(DatashareDoiCitationUpdaterCLI.class);

    private Context context;

    public DatashareDoiCitationUpdaterCLI(Context context) {
        this.context = context;
    }

    public static void main(String[] argv) {
        // create an options object and populate it
        CommandLineParser parser = new PosixParser();

        Options options = new Options();

        options.addOption("d", "register-dois", false, "Register dois for items that have no doi");
        options.addOption("c", "create citations", false, "Create citation for items that have a newly created doi");

        DatashareDoiCitationUpdaterCLI du = new DatashareDoiCitationUpdaterCLI(new Context());
        HelpFormatter helpformater = new HelpFormatter();
        try {
            CommandLine line = parser.parse(options, argv);
            if (line.hasOption('d')) {
                log.info("Started Registering DOIs");
                System.out.println("Started Registering citations");
                du.registerDois();
                log.info("Completed Registering DOIs");
                System.out.println("Completed Registering citations");
            } else if (line.hasOption('c')) {
                log.info("Started Creating citations");
                System.out.println("Started Creating citations");
                du.createCitations();
                log.info("Completed Creating citations");
                System.out.println("Completed Creating citations");
            } else {
                helpformater.printHelp("\nDataShare DOI\n", options);
            }
        } catch (ParseException ex) {
            log.info(ex);
            System.out.println(ex.getMessage());
            helpformater.printHelp("\nDataShare DOI\n", options);
        }
    }

    private void registerDois() {
        this.context.turnOffAuthorisationSystem();

        try {
            DOIIdentifierProvider doiProvider = new DSpace().getSingletonService(DOIIdentifierProvider.class);
            ItemService itemService = ContentServiceFactory.getInstance().getItemService();
            ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

            // Convert iterator to stream and process items functionally
            StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(
                            itemService.findAll(context),
                            Spliterator.ORDERED),
                    false)
                    .filter(item -> !hasEmbargo(item, itemService, configurationService))
                    .forEach(item -> processItemDoi(item, doiProvider));

            this.context.complete();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            this.context.restoreAuthSystemState();
        }
    }

    /**
     * Check if an item has an embargo using DSpace core APIs
     *
     * @param item                 The item to check
     * @param itemService          The item service
     * @param configurationService The configuration service
     * @return True if the item has an embargo
     */
    private boolean hasEmbargo(Item item, ItemService itemService, ConfigurationService configurationService) {
        boolean hasEmbargo = true;

        try {
            // Get the embargo field from configuration (default is "dc.date.available")
            String embargoField = configurationService.getProperty("embargo.field.lift", "dc.date.available");

            // Parse the embargo field to get schema, element, qualifier
            String[] fieldParts = embargoField.split("\\.");
            String schema = fieldParts.length > 0 ? fieldParts[0] : "dc";
            String element = fieldParts.length > 1 ? fieldParts[1] : "date";
            String qualifier = fieldParts.length > 2 ? fieldParts[2] : "available";

            // Get embargo metadata
            List<MetadataValue> embargoList = itemService.getMetadata(item, schema, element, qualifier, Item.ANY,
                    false);

            if (embargoList == null || embargoList.isEmpty()) {
                hasEmbargo = false;
            } else {
                // Check if embargo date has passed
                Date now = new Date();
                hasEmbargo = false; // Assume no embargo unless we find a future date

                for (MetadataValue embargoValue : embargoList) {
                    try {
                        // Parse the embargo date
                        DCDate embargoDate = new DCDate(embargoValue.getValue());
                        Date embargoDateAsDate = embargoDate.toDate();

                        // If embargo date is in the future, item is still embargoed
                        if (embargoDateAsDate != null && embargoDateAsDate.after(now)) {
                            hasEmbargo = true;
                            break;
                        }
                    } catch (Exception e) {
                        // If we can't parse the date, assume it's embargoed for safety
                        log.warn("Could not parse embargo date for item {}: {}", item.getID(), embargoValue.getValue());
                        hasEmbargo = true;
                        break;
                    }
                }
            }

            log.info("Item {} hasEmbargo: {}", item.getID(), hasEmbargo);

        } catch (Exception e) {
            log.error("Error checking embargo for item {}: {}", item.getID(), e.getMessage());
            // Default to having embargo if we can't determine
            hasEmbargo = true;
        }

        return hasEmbargo;
    }

    /**
     * Process a single item to look up or register a DOI
     *
     * @param item        The item to process
     * @param doiProvider The DOI provider service
     */
    private void processItemDoi(Item item, DOIIdentifierProvider doiProvider) {
        try {
            String doi = lookupDoi(item, doiProvider);

            if (doi == null) {
                log.info("Register doi for " + item.getID());
                try {
                    doiProvider.register(context, item);
                } catch (IdentifierException ex) {
                    log.error("*** Unable to register doi for " + item.getID());
                }
            } else {
                log.info("Item " + item.getID() + " has " + doi);
            }
        } catch (Exception e) {
            log.error("Error processing DOI for item " + item.getID() + ": " + e.getMessage());
        }
    }

    /**
     * Look up DOI for an item
     *
     * @param item        The item to look up
     * @param doiProvider The DOI provider service
     * @return The DOI if found, null otherwise
     */
    private String lookupDoi(Item item, DOIIdentifierProvider doiProvider) {
        try {
            return doiProvider.lookup(this.context, item);
        } catch (IdentifierNotResolvableException | IdentifierNotFoundException ex) {
            return null;
        }
    }

    /**
     * Create a citation for all items that have a new doi.
     */
    private void createCitations() {
        context.turnOffAuthorisationSystem();

        try {
            ItemService itemService = ContentServiceFactory.getInstance().getItemService();
            // Convert iterator to stream and process items functionally
            StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(
                            itemService.findAll(context),
                            Spliterator.ORDERED),
                    false)
                    .filter(item -> needsCitationUpdate(item))
                    .forEach(item -> processItemCitation(item));

            context.complete();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            context.restoreAuthSystemState();
        }

    }


    private boolean needsCitationUpdate(Item item) {
        ItemService itemService = ContentServiceFactory.getInstance().getItemService();
        return DatashareCitation.needsCitationUpdate(item, itemService);
    }

    private void processItemCitation(Item item) {
        try {
            ItemService itemService = ContentServiceFactory.getInstance().getItemService();
            // The batch keeps the historical "no placeholder" behaviour ("" placeholder); the
            // event-driven DatashareDoiCitationConsumer is what writes the configured placeholder at
            // install time. Both share the same builder in DatashareCitation.
            DatashareCitation.applyCitation(context, item, itemService, "");
        } catch (AuthorizeException | SQLException ex) {
            log.error("Error updating citation for item " + item.getID() + ": " + ex.getMessage());
        }
    }

}

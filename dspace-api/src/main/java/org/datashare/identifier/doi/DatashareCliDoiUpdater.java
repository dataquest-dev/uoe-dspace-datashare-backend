package org.datashare.identifier.doi;

import java.sql.SQLException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.StringTokenizer;
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

/**
 * Functionality to register items with no DOIs and update Metadata in Datashare
 * with DOI. Currently only Dublin Core dc.identifier.citation is added.
 * 
 * @author John Pinto
 * 
 * 
 */
public class DatashareCliDoiUpdater {

	private static final Logger log = LogManager.getLogger(DatashareCliDoiUpdater.class);

	private Context context;

	public DatashareCliDoiUpdater(Context context) {
		this.context = context;
	}

	public static void main(String[] argv) {
		// create an options object and populate it
		CommandLineParser parser = new PosixParser();

		Options options = new Options();

		options.addOption("d", "register-dois", false, "Register dois for items that have no doi");
		options.addOption("c", "create citations", false, "Create citation for items that have a newly created doi");

		DatashareCliDoiUpdater du = new DatashareCliDoiUpdater(new Context());
		HelpFormatter helpformater = new HelpFormatter();
		try {
			CommandLine line = parser.parse(options, argv);
			if (line.hasOption('d')) {
				du.registerDios();
			} else if (line.hasOption('c')) {
				du.createCitations();
			} else {
				helpformater.printHelp("\nDataShare DOI\n", options);
			}
		} catch (ParseException ex) {
			log.info(ex);
			helpformater.printHelp("\nDataShare DOI\n", options);
		}
	}

	private void registerDios() {
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

		// Get citation directly using DSpace API
		List<MetadataValue> citations = itemService.getMetadata(item, "dc", "identifier", "citation", Item.ANY, false);
		String citation = citations.isEmpty() ? null : citations.get(0).getValue();

		// Check if item has DOI directly using DSpace API
		List<MetadataValue> identifiers = itemService.getMetadata(item, "dc", "identifier", "uri", Item.ANY, false);
		boolean hasDoi = identifiers.stream()
				.anyMatch(identifier -> identifier.getValue().startsWith("https://doi.org"));

		log.info("Item {} citation: '{}' hasDoi: {}", item.getID(), citation, hasDoi);

		// Case 1: Has DOI but no citation
		boolean needsNewCitation = hasDoi && citation == null;

		// Case 2: Has citation but it doesn't contain the DOI URL
		boolean needsUpdatedCitation = citation != null && !citation.contains("https://doi.org");

		log.info("Item {} needsNewCitation: {} needsUpdatedCitation: {}",
				item.getID(), needsNewCitation, needsUpdatedCitation);

		return needsNewCitation || needsUpdatedCitation;
	}

	private void processItemCitation(Item item) {
		try {
			ItemService itemService = ContentServiceFactory.getInstance().getItemService();

			// Get current citation
			List<MetadataValue> citations = itemService.getMetadata(item, "dc", "identifier", "citation", Item.ANY,
					false);
			String citation = citations.isEmpty() ? null : citations.get(0).getValue();

			// Check if item has DOI
			List<MetadataValue> identifiers = itemService.getMetadata(item, "dc", "identifier", "uri", Item.ANY, false);
			boolean hasDoi = identifiers.stream()
					.anyMatch(identifier -> identifier.getValue().startsWith("https://doi.org"));

			log.info("Item " + item.getID() + " citation: " + citation);

			if (citation == null && hasDoi) {
				// Create new citation
				String newCitation = createCitation(item);
				if (newCitation != null) {
					itemService.addMetadata(context, item, "dc", "identifier", "citation", "en", newCitation);
				}
			} else if (citation != null && !citation.contains("https://doi.org") && hasDoi) {
				// Clear existing citation and create new one
				itemService.clearMetadata(context, item, "dc", "identifier", "citation", Item.ANY);
				String newCitation = createCitation(item);
				if (newCitation != null) {
					itemService.addMetadata(context, item, "dc", "identifier", "citation", "en", newCitation);
					log.info("Item " + item.getID() + " has new citation: " + newCitation);
				}
			}

			itemService.update(context, item);
		} catch (AuthorizeException | SQLException ex) {
			log.error("Error updating citation for item " + item.getID() + ": " + ex.getMessage());
		}
	}

	/**
	 * Create a citation for a given DSpace item using DSpace core APIs
	 */
	private String createCitation(Item item) {
		try {
			ItemService itemService = ContentServiceFactory.getInstance().getItemService();
			StringBuilder buffer = new StringBuilder(200);

			// Get creators
			List<MetadataValue> creators = itemService.getMetadata(item, "dc", "creator", Item.ANY, Item.ANY, false);
			boolean creatorGiven = !creators.isEmpty();

			if (creatorGiven) {
				// Add creators
				for (int i = 0; i < creators.size(); i++) {
					if (i > 0) {
						buffer.append("; ");
					}
					buffer.append(creators.get(i).getValue());
				}
				buffer.append(". ");
			} else {
				// Add publisher if no creators
				List<MetadataValue> publishers = itemService.getMetadata(item, "dc", "publisher", Item.ANY, Item.ANY,
						false);
				if (!publishers.isEmpty()) {
					buffer.append(" ");
					buffer.append(publishers.get(0).getValue());
					buffer.append(".");
				}
				buffer.append(" ");
			}

			// Add date available year if available
			buffer.append("(");
			List<MetadataValue> dateAvailable = itemService.getMetadata(item, "dc", "date", "available", Item.ANY,
					false);
			if (!dateAvailable.isEmpty()) {
				String dateStr = dateAvailable.get(0).getValue();
				// Extract year from date string (assuming format like "2023-01-01" or "2023")
				String year = dateStr.length() >= 4 ? dateStr.substring(0, 4) : dateStr;
				buffer.append(year);
			} else {
				// No date available, use current year
				Calendar calendar = new GregorianCalendar();
				calendar.setTime(new Date());
				buffer.append(calendar.get(Calendar.YEAR));
			}
			buffer.append("). ");

			// Add title
			List<MetadataValue> titles = itemService.getMetadata(item, "dc", "title", Item.ANY, Item.ANY, false);
			if (!titles.isEmpty()) {
				buffer.append(titles.get(0).getValue());
			}
			buffer.append(", ");

			// Add time period if available
			List<MetadataValue> temporal = itemService.getMetadata(item, "dc", "coverage", "temporal", Item.ANY, false);
			if (!temporal.isEmpty()) {
				String timePeriod = temporal.get(0).getValue();
				String[] dates = decodeTimePeriod(timePeriod);

				if (dates != null && dates.length == 2) {
					String from = dates[0].length() >= 4 ? dates[0].substring(0, 4) : dates[0];
					String to = dates[1].length() >= 4 ? dates[1].substring(0, 4) : dates[1];

					if (from.equals(to)) {
						timePeriod = from;
					} else {
						timePeriod = from + "-" + to;
					}

					buffer.append(timePeriod);
					buffer.append(" ");
				}
			}

			// Add item type
			List<MetadataValue> types = itemService.getMetadata(item, "dc", "type", Item.ANY, Item.ANY, false);
			buffer.append("[");
			if (!types.isEmpty()) {
				buffer.append(types.get(0).getValue());
			}
			buffer.append("].");

			// Append publisher if creator is specified
			if (creatorGiven) {
				List<MetadataValue> publishers = itemService.getMetadata(item, "dc", "publisher", Item.ANY, Item.ANY,
						false);
				if (!publishers.isEmpty()) {
					buffer.append(" ");
					buffer.append(publishers.get(0).getValue());
					buffer.append(".");
				}
			}

			// Add DOI if available
			List<MetadataValue> identifiers = itemService.getMetadata(item, "dc", "identifier", "uri", Item.ANY, false);
			for (MetadataValue identifier : identifiers) {
				if (identifier.getValue().startsWith("https://doi.org")) {
					buffer.append(" ");
					buffer.append(identifier.getValue());
					buffer.append(".");
					break;
				}
			}

			return buffer.toString();

		} catch (Exception e) {
			log.error("Error creating citation for item " + item.getID() + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * Decode time period W3CDTF profile of ISO 8601.
	 * (Copied from DatashareDspaceUtils since we can't use it)
	 */
	private String[] decodeTimePeriod(String encoding) {
		String[] dates = null;

		if (encoding != null) {
			String startStr = null;
			String endStr = null;

			// get tokens delimited by ";"- there should be three -
			// start=, end= and scheme=
			StringTokenizer st = new StringTokenizer(encoding, ";");

			if (st.countTokens() > 1) {
				for (int i = 0; i < st.countTokens(); i++) {
					if (i == 0) {
						startStr = st.nextToken();
					} else if (i == 1) {
						endStr = st.nextToken();
					} else {
						break;
					}
				}

				String startArray[] = startStr.split("=");
				String endArray[] = endStr.split("=");

				if (startArray.length == 2 || endArray.length == 2) {
					dates = new String[2];
				}

				if (startArray.length == 2) {
					dates[0] = startArray[1];
				}

				if (endArray.length == 2) {
					dates[1] = endArray[1];
				}
			}
		}

		return dates;
	}

}

package org.datashare.ctask;

import java.io.IOException;
import java.sql.SQLException;

import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.curate.AbstractCurationTask;
import org.dspace.curate.Curator;
import org.dspace.curate.Distributive;
import org.dspace.services.factory.DSpaceServicesFactory;

/**
 * A curation job to check if an item's bitstreams are too large to be emailed
 * together and which of the item's bitstreams cannot be emailed separately
 * because of size.
 * 
 * @author John Pinto
 *
 */

 @Distributive
 public class EmailableBitstreamsChecker extends AbstractCurationTask {
 
    /**
     * log4j category
     */
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(EmailableBitstreamsChecker.class);
 
     private static final String EMAILABLE_BITSTREAMS_MAX_SIZE_IN_MB_KEY = "emailable.bitstreams.max.size";
     private static final int DEFAULT_EMAILABLE_BITSTREAMS_MAX_SIZE_IN_MB = 100;
     private static final long ONE_MEGABYTE_SIZE_IN_BYTES = 1024L * 1024L;
 
     private static final String DS_METADATA_SCHEMA = "ds";
     private static final String NOT_EMAILABLE_METADATA_ELEMENT = "not-emailable";
     private static final String BITSTREAM_METADATA_QUALIFIER = "bitstream";
     private static final String ITEM_METADATA_QUALIFIER = "item";
     private static final String METADATA_LANGUAGE = "en";
     private static final String TRUE = "true";
 
     private int status = Curator.CURATE_UNSET;
     private long maxAllowedBitstreamsSizeInBytes;
 
     private long totalBitstreamSize = 0L;
 
     @Override
     public void init(Curator curator, String taskId) throws IOException {
         super.init(curator, taskId);
         maxAllowedBitstreamsSizeInBytes = DSpaceServicesFactory.getInstance().getConfigurationService().getLongProperty(EMAILABLE_BITSTREAMS_MAX_SIZE_IN_MB_KEY,
                 DEFAULT_EMAILABLE_BITSTREAMS_MAX_SIZE_IN_MB) * ONE_MEGABYTE_SIZE_IN_BYTES;
     }
 
     /**
      * Add metadata for non-emailable bitreams and the corresponding item.
      *
      * @param dso The DSpaceObject to be checked
      * @return The curation task status of the checking
      */
     @Override
     public int perform(DSpaceObject dso) {
         // The results that we'll return
         StringBuilder results = new StringBuilder();
 
         // Unless this is an item, we'll skip this item
         status = Curator.CURATE_SKIP;
         log.info("The target dso is " + dso.getName());
         if (dso instanceof Item) {
             boolean itemNotEmailableMetadataSet = false;
             try {
                 Item item = (Item) dso;
 
                 ItemService itemService = ContentServiceFactory.getInstance().getItemService();
                 Context context = new Context();
                 context.turnOffAuthorisationSystem();
 
                 itemService.clearMetadata(context, item, DS_METADATA_SCHEMA, NOT_EMAILABLE_METADATA_ELEMENT,
                         ITEM_METADATA_QUALIFIER, METADATA_LANGUAGE);
 
                 BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
 
                 for (Bundle bundle : item.getBundles()) {
 
                     if ("ORIGINAL".equals(bundle.getName())) {
                         for (Bitstream bitstream : bundle.getBitstreams()) {
                             totalBitstreamSize += bitstream.getSizeBytes();
                             log.info("The bitstream size (bytes): " + bitstream.getSizeBytes());
                             if (bitstream.getSizeBytes() > maxAllowedBitstreamsSizeInBytes) {
                                 addItemMetaData(item, itemNotEmailableMetadataSet);
                                 itemNotEmailableMetadataSet = true;
                                 addBitstreamMetaData(bitstream);
                                 bitstreamService.update(context, bitstream);
 
                             }
                         }
                     } else if ("THUMBNAIL".equals(bundle.getName())) {
                         for (Bitstream bitstream : bundle.getBitstreams()) {
                             totalBitstreamSize += bitstream.getSizeBytes();
                             log.info("The bitstream size (bytes): " + bitstream.getSizeBytes());
                             if (bitstream.getSizeBytes() > maxAllowedBitstreamsSizeInBytes) {
                                 addItemMetaData(item, itemNotEmailableMetadataSet);
                                 itemNotEmailableMetadataSet = true;
                                 addBitstreamMetaData(bitstream);
                                 bitstreamService.update(context, bitstream);
                             }
                         }
                     }
                 }
 
                 log.info("The total item's bitstreams size (bytes): " + totalBitstreamSize);
                 // If the total size of the bitstreams exceeds the max-size then update the item
                 // metadata.
                 if (totalBitstreamSize > maxAllowedBitstreamsSizeInBytes) {
                     addItemMetaData(item, itemNotEmailableMetadataSet);
                     itemNotEmailableMetadataSet = true;
                 }
                 itemService.update(context, item);
                 status = Curator.CURATE_SUCCESS;
                 results.append("CURATE_SUCCESS");
 
             } catch (AuthorizeException ae) {
                 // Something went wrong
                 log.info(ae.getMessage());
                 status = Curator.CURATE_ERROR;
                 results.append("CURATE_ERROR");
             } catch (SQLException sqle) {
                 // Something went wrong
                 log.info(sqle.getMessage());
                 status = Curator.CURATE_ERROR;
                 results.append("CURATE_ERROR");
             }
 
         }
 
         log.info("About to report: " + results.toString());
         setResult(results.toString());
         report(results.toString());
 
         return status;
     }
 
     // Add metadata to item object for a non-emailable item's bitstreams because of
     // total size.
     private void addItemMetaData(Item item, boolean alreadySet) throws SQLException {
         // Only update if not already set
         if (!alreadySet) {
             ItemService itemService = ContentServiceFactory.getInstance().getItemService();
             Context context = new Context();
             context.turnOffAuthorisationSystem();
             itemService.addMetadata(context, item, DS_METADATA_SCHEMA, NOT_EMAILABLE_METADATA_ELEMENT,
                     ITEM_METADATA_QUALIFIER, METADATA_LANGUAGE, TRUE);
         }
     }
 
     // Add metadata to the bitstream object if it is non-emailable.
     private void addBitstreamMetaData(Bitstream bitstream) throws SQLException {
         BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
         Context context = new Context();
         context.turnOffAuthorisationSystem();
         // Clear metadata field we plan to update
         bitstreamService.clearMetadata(context, bitstream, DS_METADATA_SCHEMA, NOT_EMAILABLE_METADATA_ELEMENT,
                 BITSTREAM_METADATA_QUALIFIER, METADATA_LANGUAGE);
         // Add metadata
         bitstreamService.addMetadata(context, bitstream, DS_METADATA_SCHEMA, NOT_EMAILABLE_METADATA_ELEMENT,
                 BITSTREAM_METADATA_QUALIFIER, METADATA_LANGUAGE, TRUE);
     }
 
 }
 

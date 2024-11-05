package org.dspace.util.datashare;

import java.util.List;

import org.apache.logging.log4j.Logger;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;

public class DatashareUtils {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareUtils.class);

    private static final String DATASHARE_SCHEMA = "ds";
    private static final String TOMBSTONE_ELEMENT = "withdrawn";
	private static final String TOMBSTONE_SHOW_QUALIFIER = "showtombstone";


    public static boolean hasEmbargo(Context context, Item item) {
        boolean hasEmbargo = true;
        ItemService itemService = ContentServiceFactory.getInstance().getItemService();
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        
        List<MetadataValue> embargoList = itemService.getMetadataByMetadataString(item, configurationService.getProperty("embargo.field.lift"));
        if(embargoList == null || embargoList.size() == 0){
            hasEmbargo = false;
        }
        
        log.info(item.getID() + " hasEmbargo: " + hasEmbargo);
        
        return hasEmbargo;
    }


}

package org.dspace.util.datashare;

import java.sql.SQLException;
import java.util.Enumeration;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;

import jakarta.servlet.http.HttpServletRequest;

public class DatashareUtils {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareUtils.class);

    private static final String DATASHARE_SCHEMA = "ds";
    private static final String TOMBSTONE_ELEMENT = "withdrawn";
    private static final String TOMBSTONE_SHOW_QUALIFIER = "showtombstone";

    public static boolean hasEmbargo(Context context, Item item) {
        boolean hasEmbargo = true;
        if (item == null) {
            return true;
        }
        ItemService itemService = ContentServiceFactory.getInstance().getItemService();
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

        List<MetadataValue> embargoList = itemService.getMetadataByMetadataString(item,
                configurationService.getProperty("embargo.field.lift"));
        if (embargoList == null || embargoList.size() == 0) {
            hasEmbargo = false;
        }

        log.info(item + " hasEmbargo: " + hasEmbargo);

        return hasEmbargo;
    }

    /**
     * Should download all option be allowed? Download all link will not be
     * visible if the item has an embargo, if the total download is greater than
     * dspace.downloadall.limit or the item is withdrawn.
     * 
     * @param context
     * @param item
     * @return True if download all option should be shown.
     */
    public static boolean allowDownloadAll(Context context, Item item) {
        boolean allow = !hasEmbargo(context, item) &&
                canReadAllBitstreams(context, item) &&
                !item.isWithdrawn() &&
                !showTombstone(context, item);

        if (!allow) {
            log.warn("Download not allowed: hasEmbargo " + hasEmbargo(context, item) +
                    ". canReadAllBitstreams: " + canReadAllBitstreams(context, item) +
                    ". isWithdrawn: " + item.isWithdrawn() +
                    ". showTombstone: " + showTombstone(context, item));
        }

        return allow;
    }

    public static boolean showTombstone(Context context, Item item) {
        boolean show = false;

        try {
            AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
            if (!authorizeService.isAdmin(context)) {
                String tomb = DatashareMetaDataUtils.getShowTombstone(item);
                if (tomb != null) {
                    show = Boolean.parseBoolean(tomb);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Problem determining access right", ex);
        }

        return show;
    }

    public static boolean canReadAllBitstreams(Context context, Item item) {
        boolean canRead = true;
        try {
            ItemService itemService = ContentServiceFactory.getInstance().getItemService();
            itemService.updateLastModified(context, item);
            List<Bundle> bundles = itemService.getBundles(item, "ORIGINAL");

            for (int i = 0; i < bundles.size() && canRead; i++) {
                List<Bitstream> files = bundles.get(i).getBitstreams();
                for (int j = 0; j < files.size() && canRead; j++) {
                    AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
                    // System.out.println(j);
                    canRead = authorizeService.getPoliciesActionFilter(
                            context,
                            files.get(j),
                            Constants.READ).size() > 0 && authorizeService.getPoliciesActionFilter(
                                context,
                                files.get(j),
                                Constants.WITHDRAWN_READ).size() == 0;
                    log.info(item.getID() + ": file index: " + j + ", canRead: " + canRead);
                    if (canRead) {
                        break;
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } catch (AuthorizeException e) {
            throw new RuntimeException(e);
        }

        log.info(item.getID() + " canReadAllBitstreams: " + canRead);

        return canRead;
    }

    /**
     * @param request HTTP request.
     * @return User's ip address
     */
    public static String getIPAddress(HttpServletRequest request) {
        // debuglogRequestHeaders(request);
        // Set the session ID and IP address
        String ip = request.getRemoteAddr();
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        if (!configurationService.getBooleanProperty("useProxies", false)) {
            // DATASHARE - start
            // (reversed previous if order to ensure NS-X-Forwarded-For looked for first)
            if (request.getHeader("NS-X-Forwarded-For") != null) {
                ip = getXForwardedFor("NS-X-Forwarded-For", request);
            } else if (request.getHeader("X-Forwarded-For") != null) {
                ip = getXForwardedFor("X-Forwarded-For", request);
            }
            // DATASHARE - end
        }

        return ip;
    }

    // Code to display Request Headers for debugging
    private static void debuglogRequestHeaders(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Enumeration<String> headerNames = request.getHeaderNames();
        log.debug("----------------------------------------------------------------------");
        log.debug("Header Names:");

        while (headerNames.hasMoreElements()) {
            String key = (String) headerNames.nextElement();
            String value = request.getHeader(key);
            log.debug(key + ": " + value);
        }
        log.debug("----------------------------------------------------------------------");

    }

    /**
     * @param header  HTTP header.
     * @param request HTTP request.
     * @return X Forwarded for address.
     */
    private static String getXForwardedFor(String header, HttpServletRequest request) {
        String ip = null;
        if (request.getHeader(header) != null) {
            ip = request.getHeader(header).trim();
        }
        log.debug(header + ": " + ip);
        return ip;
    }
    // DATASHARE - end

}

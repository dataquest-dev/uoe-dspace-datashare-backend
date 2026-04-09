/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.datashare;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.content.Item;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/datashare")
public class DatashareDatasetRestController {

    @Autowired
    private DatashareDatasetService datashareDatasetService;

    @Autowired
    private ItemService itemService;

    @RequestMapping(value = "/items/{id}/zip-file-link", method = RequestMethod.GET)
    public String fetchDatashareDatasetZipFileLink(@PathVariable(value = "id") String itemId,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        Context context = ContextUtil.obtainContext(request);
        Item item = itemService.find(context, UUID.fromString(itemId));

        return datashareDatasetService.fetchDatashareDatasetZipFileLink(context, item);
    }

    @RequestMapping(value = "/items/{id}/zip-file-downloadable", method = RequestMethod.GET)
    public Boolean isDatashareDatasetZipFileDownloadable(@PathVariable(value = "id") String itemId,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        Context context = ContextUtil.obtainContext(request);
        Item item = itemService.find(context, UUID.fromString(itemId));

        return datashareDatasetService.isDatashareDatasetZipFileDownloadable(context, item);

    }
}

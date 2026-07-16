/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit.factory.impl;

import java.sql.SQLException;
import java.text.ParseException;
import java.util.Date;
import java.util.List;

import org.dspace.app.rest.exception.RESTAuthorizationException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.AccessConditionDTO;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.DSpaceObject;
import org.dspace.core.Context;
import org.dspace.submit.model.AccessConditionOption;
import org.dspace.submit.model.UploadConfiguration;

/**
 * Utility class to reuse methods related to bitstream resource-policies
 * These methods are applicable in the submission when adding or editing bitstreams, and the resource policies
 * have to be applied
 */
public class BitstreamResourcePolicyUtils {

    /**
     * Default constructor
     */
    private BitstreamResourcePolicyUtils() { }

    /**
     * Enforce that only site administrators may modify bitstream access conditions during the
     * submission process (UoE DataShare customization, see dspace-customers issue #801).
     *
     * @param context          The relevant DSpace Context.
     * @param authorizeService The service used for the administrator check
     * @throws SQLException               If a database error occurs
     * @throws RESTAuthorizationException If the current user is not a site administrator
     */
    public static void requireAdminForAccessConditions(Context context, AuthorizeService authorizeService)
            throws SQLException {
        if (!authorizeService.isAdmin(context)) {
            throw new RESTAuthorizationException(
                "Only site administrators may modify bitstream access conditions during submission");
        }
    }

    /**
     * Based on the given access condition, find the resource policy to apply on the given DSpace object
     * This function applies the resource policies.
     *
     * @param context               The relevant DSpace Context.
     * @param uploadConfiguration   The configured UploadConfiguration
     * @param obj                   The applicable DSpace object whose policies should be determined
     * @param newAccessConditions   The access condition containing the details for the desired policies
     * @throws SQLException         If a database error occurs
     * @throws AuthorizeException   If the user is not authorized
     * @throws ParseException       If parse error
     */
    public static void findApplyResourcePolicy(Context context, UploadConfiguration uploadConfiguration,
            DSpaceObject obj, List<AccessConditionDTO> newAccessConditions)
            throws SQLException, AuthorizeException, ParseException {
        for (AccessConditionDTO newAccessCondition : newAccessConditions) {
            String name = newAccessCondition.getName();
            String description = newAccessCondition.getDescription();

            Date startDate = newAccessCondition.getStartDate();
            Date endDate = newAccessCondition.getEndDate();

            findApplyResourcePolicy(context, uploadConfiguration, obj, name, description, startDate, endDate);
        }

    }

    /**
     * Based on the given name, find the resource policy to apply on the given DSpace object
     * This function applies the resource policies.The description, start date and end date are applied as well
     *
     * @param context               The relevant DSpace Context.
     * @param uploadConfiguration   The configured UploadConfiguration
     * @param obj                   The applicable DSpace object whose policies should be determined
     * @param name                  The name of the access condition matching the desired policies
     * @param description           An optional description for the policies
     * @param startDate             An optional start date for the policies
     * @param endDate               An optional end date for the policies
     * @throws SQLException         If a database error occurs
     * @throws AuthorizeException   If the user is not authorized
     * @throws ParseException       If parse error
     */
    public static void findApplyResourcePolicy(Context context, UploadConfiguration uploadConfiguration,
            DSpaceObject obj, String name, String description,
                                               Date startDate, Date endDate)
            throws SQLException, AuthorizeException, ParseException {
        boolean found = false;
        for (AccessConditionOption aco : uploadConfiguration.getOptions()) {
            if (aco.getName().equalsIgnoreCase(name)) {
                aco.createResourcePolicy(context, obj, name, description, startDate, endDate);
                found = true;
                break;
            }
        }
        // unexisting/unconfigured access conditions are no longer accepted
        if (!found) {
            throw new UnprocessableEntityException("The provided access condition: " + name + " is not supported!");
        }
        return;
    }
}

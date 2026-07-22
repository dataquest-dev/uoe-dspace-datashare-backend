/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.util;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.GroupService;

/**
 * The Access Control List of a submission form field, parsed from its {@code <acl>} element and
 * evaluated when the form is rendered.
 * <p>
 * The semantics are inherited from the LINDAT/CLARIAH-CZ fork and each of them is a
 * configuration foot-gun worth knowing about:
 * <ul>
 * <li>an empty ACL allows everything, for backwards compatibility with fields that have no
 * {@code <acl>} element;</li>
 * <li>a site administrator ({@code Group.ADMIN} membership) is always allowed — collection
 * administrators, community administrators and workflow reviewers are not;</li>
 * <li>matching is first-match-wins with an implicit default of deny, so an entry that allows
 * one action implicitly denies the other;</li>
 * <li>an entry that cannot be parsed is dropped, which leaves the field unguarded.</li>
 * </ul>
 * This hides a field from the REST form payload. It is not write-path enforcement: anything
 * that must not be done by a non-administrator has to be refused server side as well.
 *
 * @author Michal Josífko
 * Class is copied from the LINDAT/CLARIAH-CZ (https://github.com/ufal/clarin-dspace) and modified by
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class ACL {

    /** Logger */
    private static final Logger log = LogManager.getLogger(ACL.class);
    public static final int ACTION_READ = ACE.ACTION_READ;
    public static final int ACTION_WRITE = ACE.ACTION_WRITE;
    /**
     * List of single Access Control Entry
     */
    private List<ACE> acl;
    protected AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
    protected GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();

    /**
     * Creates new ACL object from given String
     *
     * @param aclDefinition of the field from the form definition file, may be null
     * @return ACL object, empty if the definition is null or holds no parsable entry
     */
    public static ACL fromString(String aclDefinition) {
        List<ACE> acl = new ArrayList<ACE>();
        if (aclDefinition != null) {
            String[] aclEntries = aclDefinition.split(";");
            for (int i = 0; i < aclEntries.length; i++) {
                String aclEntry = aclEntries[i];
                ACE ace = ACE.fromString(aclEntry);
                if (ace != null) {
                    acl.add(ace);
                }
            }
        }
        return new ACL(acl);
    }

    /**
     * Constructor for creating new Access Control List
     *
     * @param acl List of ACE
     */
    ACL(List<ACE> acl) {
        this.acl = acl;
    }

    /**
     * Method to verify whether the the given user ID and set of group IDs is
     * allowed to perform the given action
     *
     * @param userID current user
     * @param groupIDs where is assigned the current user
     * @param action read/write
     * @return if user will see the input field
     */
    private boolean isAllowedAction(String userID, Set<String> groupIDs, int action) {
        for (ACE ace : acl) {
            if (ace.matches(userID, groupIDs, action)) {
                return ace.isAllowed();
            }
        }
        return false;
    }

    /**
     * Convenience method to verify whether the current user is allowed to
     * perform given action based on current context
     *
     * @param c Current context, the user information are loaded from the context. Outside an
     *          HTTP request there is none, in which case a non-empty ACL fails closed.
     * @param action read/write
     * @return if user will see the input field
     */
    public boolean isAllowedAction(Context c, int action) {
        boolean res = false;
        if (acl.isEmpty()) {
            // To maintain backwards compatibility allow everything if the ACL
            // is empty
            return true;
        }
        if (c == null) {
            return false;
        }
        try {
            if (authorizeService.isAdmin(c)) {
                // Admin is always allowed
                return true;
            } else {
                EPerson e = c.getCurrentUser();
                if (e != null) {
                    UUID userID = e.getID();
                    List<Group> groups = groupService.allMemberGroups(c, c.getCurrentUser());

                    Set<String> groupIDs = groups.stream().flatMap(group -> Stream.of(group.getID().toString()))
                            .collect(Collectors.toSet());

                    return isAllowedAction(userID.toString(), groupIDs, action);
                }
            }
        } catch (SQLException e) {
            log.error("Could not evaluate the ACL for action {}, refusing it", action, e);
        }
        return res;
    }

    /**
     * Returns true is the ACL is empty set of rules
     *
     * @return contains some ACE elements
     */
    public boolean isEmpty() {
        return acl.isEmpty();
    }

}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.util;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.dspace.AbstractUnitTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.GroupService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the semantics of the {@code <acl>} element of a submission form field, as evaluated by
 * {@link ACL} and {@link ACE}. These semantics are inherited from the LINDAT/CLARIAH-CZ fork and
 * are relied upon by the submission form converter, so they must not drift.
 */
public class ACLTest extends AbstractUnitTest {

    /**
     * The entry used by the DataShare "upload from server path" field: nobody but a site
     * administrator may see it.
     */
    private static final String DENY_READ_ANY_USER = "policy=deny,action=read,grantee-type=user,grantee-id=*";

    private static final String ALLOW_READ_ANY_USER = "policy=allow,action=read,grantee-type=user,grantee-id=*";

    protected GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();

    /**
     * Spies of the autowired (global) beans, so that individual tests can decide what the
     * ACL sees without touching the database.
     */
    private AuthorizeService authorizeServiceSpy;
    private GroupService groupServiceSpy;

    @Before
    @Override
    public void init() {
        super.init();

        authorizeServiceSpy = spy(authorizeService);
        groupServiceSpy = spy(groupService);
    }

    /**
     * A field without an {@code <acl>} must behave exactly as it did before the ACL machinery
     * existed, for every action. No stubbing here on purpose: the short-circuit has to fire
     * before anything is asked about the current user.
     */
    @Test
    public void emptyAclAllowsEveryAction() {
        ACL acl = ACL.fromString(null);

        assertThat("emptyAclAllowsEveryAction 0", acl.isEmpty(), equalTo(true));
        assertThat("emptyAclAllowsEveryAction 1", acl.isAllowedAction(context, ACL.ACTION_READ), equalTo(true));
        assertThat("emptyAclAllowsEveryAction 2", acl.isAllowedAction(context, ACL.ACTION_WRITE), equalTo(true));
    }

    /**
     * A deny entry matching every user hides the field from an ordinary user, and the implicit
     * default-deny tail hides it for the action the entry does not even mention.
     */
    @Test
    public void denyForEveryUserRefusesANonAdmin() throws SQLException {
        ACL acl = stubbedAcl(DENY_READ_ANY_USER, false);

        assertThat("denyForEveryUserRefusesANonAdmin 0", acl.isEmpty(), equalTo(false));
        assertThat("denyForEveryUserRefusesANonAdmin 1", acl.isAllowedAction(context, ACL.ACTION_READ),
                   equalTo(false));
        assertThat("denyForEveryUserRefusesANonAdmin 2", acl.isAllowedAction(context, ACL.ACTION_WRITE),
                   equalTo(false));
    }

    /**
     * A site administrator bypasses the ACL entirely, which is what makes the DataShare field
     * admin-only rather than invisible to everyone.
     */
    @Test
    public void denyForEveryUserIsBypassedForSiteAdmin() throws SQLException {
        ACL acl = stubbedAcl(DENY_READ_ANY_USER, true);

        assertThat("denyForEveryUserIsBypassedForSiteAdmin 0", acl.isAllowedAction(context, ACL.ACTION_READ),
                   equalTo(true));
        assertThat("denyForEveryUserIsBypassedForSiteAdmin 1", acl.isAllowedAction(context, ACL.ACTION_WRITE),
                   equalTo(true));
    }

    /**
     * An entry allowing one action implicitly denies the other: "readable but not writable"
     * cannot be expressed with a single entry.
     */
    @Test
    public void allowReadDoesNotImplyWrite() throws SQLException {
        ACL acl = stubbedAcl(ALLOW_READ_ANY_USER, false);

        assertThat("allowReadDoesNotImplyWrite 0", acl.isAllowedAction(context, ACL.ACTION_READ), equalTo(true));
        assertThat("allowReadDoesNotImplyWrite 1", acl.isAllowedAction(context, ACL.ACTION_WRITE), equalTo(false));
    }

    /**
     * A typo inside an entry must be logged and dropped rather than break form parsing at
     * startup. The field then simply has no ACL, which is why the server-side authorisation
     * check is the real protection.
     */
    @Test
    public void malformedEntryIsDroppedInsteadOfThrowing() {
        ACL acl = ACL.fromString("policy=deny,action=read,grantee_type=user,grantee-id=*");

        assertThat("malformedEntryIsDroppedInsteadOfThrowing 0", acl.isEmpty(), equalTo(true));
        assertThat("malformedEntryIsDroppedInsteadOfThrowing 1", acl.isAllowedAction(context, ACL.ACTION_READ),
                   equalTo(true));
    }

    /**
     * A malformed entry must not take the well-formed entries beside it down with it.
     */
    @Test
    public void malformedEntryDoesNotDisableTheRemainingEntries() throws SQLException {
        ACL acl = stubbedAcl("nonsense;" + DENY_READ_ANY_USER, false);

        assertThat("malformedEntryDoesNotDisableTheRemainingEntries 0", acl.isEmpty(), equalTo(false));
        assertThat("malformedEntryDoesNotDisableTheRemainingEntries 1",
                   acl.isAllowedAction(context, ACL.ACTION_READ), equalTo(false));
    }

    /**
     * A user grantee identified by UUID matches that user and nobody else.
     */
    @Test
    public void userGranteeMatchesOnlyThatUser() throws SQLException {
        ACL mine = stubbedAcl("policy=allow,action=read,grantee-type=user,grantee-id=" + eperson.getID(), false);
        ACL someoneElse = stubbedAcl("policy=allow,action=read,grantee-type=user,grantee-id="
                                         + UUID.randomUUID(), false);

        assertThat("userGranteeMatchesOnlyThatUser 0", mine.isAllowedAction(context, ACL.ACTION_READ),
                   equalTo(true));
        assertThat("userGranteeMatchesOnlyThatUser 1", someoneElse.isAllowedAction(context, ACL.ACTION_READ),
                   equalTo(false));
    }

    /**
     * A group grantee matches when the current user is a member of that group. The user is
     * stubbed as a non-administrator so that the assertion can only be satisfied by the group
     * matching, never by the administrator bypass.
     */
    @Test
    public void groupGranteeMatchesAGroupTheUserBelongsTo() throws SQLException, AuthorizeException {
        Group group = createGroup("ACLTest members");
        ACL acl = stubbedAcl("policy=allow,action=read,grantee-type=group,grantee-id=" + group.getID(),
                             false, group);

        assertThat("groupGranteeMatchesAGroupTheUserBelongsTo 0", acl.isAllowedAction(context, ACL.ACTION_READ),
                   equalTo(true));
    }

    /**
     * A group grantee does not match a group the current user is not a member of.
     */
    @Test
    public void groupGranteeDoesNotMatchAnUnrelatedGroup() throws SQLException, AuthorizeException {
        Group memberOf = createGroup("ACLTest members");
        Group unrelated = createGroup("ACLTest outsiders");
        ACL acl = stubbedAcl("policy=allow,action=read,grantee-type=group,grantee-id=" + unrelated.getID(),
                             false, memberOf);

        assertThat("groupGranteeDoesNotMatchAnUnrelatedGroup 0", acl.isAllowedAction(context, ACL.ACTION_READ),
                   equalTo(false));
    }

    /**
     * Outside an HTTP request there is no Context to resolve the current user from. An
     * unguarded field is still allowed, but a guarded one fails closed instead of throwing:
     * the submission form converter relies on this.
     */
    @Test
    public void nullContextFailsClosedForAGuardedField() {
        assertThat("nullContextFailsClosedForAGuardedField 0",
                   ACL.fromString(null).isAllowedAction(null, ACL.ACTION_READ), equalTo(true));
        assertThat("nullContextFailsClosedForAGuardedField 1",
                   ACL.fromString(DENY_READ_ANY_USER).isAllowedAction(null, ACL.ACTION_READ), equalTo(false));
        assertThat("nullContextFailsClosedForAGuardedField 2",
                   ACL.fromString(ALLOW_READ_ANY_USER).isAllowedAction(null, ACL.ACTION_READ), equalTo(false));
    }

    /**
     * Builds an ACL that sees a fully stubbed world, so that no assertion below depends on
     * incidental properties of the shared unit-test EPerson - in particular on it not being a
     * member of the Administrator group, which would silently satisfy the admin bypass instead
     * of the rule under test.
     */
    private ACL stubbedAcl(String definition, boolean admin, Group... memberOf) throws SQLException {
        ACL acl = ACL.fromString(definition);
        doReturn(admin).when(authorizeServiceSpy).isAdmin(context);
        doReturn(List.of(memberOf)).when(groupServiceSpy).allMemberGroups(context, eperson);
        ReflectionTestUtils.setField(acl, "authorizeService", authorizeServiceSpy);
        ReflectionTestUtils.setField(acl, "groupService", groupServiceSpy);
        return acl;
    }

    private Group createGroup(String name) throws SQLException, AuthorizeException {
        context.turnOffAuthorisationSystem();
        try {
            Group group = groupService.create(context);
            // an unnamed group breaks unrelated queries, so give it one even though we only use its id
            groupService.setName(group, name);
            groupService.update(context, group);
            return group;
        } finally {
            context.restoreAuthSystemState();
        }
    }
}

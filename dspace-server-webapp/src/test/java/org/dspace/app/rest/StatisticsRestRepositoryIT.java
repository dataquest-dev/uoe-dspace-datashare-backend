/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.apache.commons.codec.CharEncoding.UTF_8;
import static org.apache.commons.io.IOUtils.toInputStream;
import static org.dspace.app.rest.utils.UsageReportUtils.TOP_CITIES_REPORT_ID;
import static org.dspace.app.rest.utils.UsageReportUtils.TOP_COUNTRIES_REPORT_ID;
import static org.dspace.app.rest.utils.UsageReportUtils.TOTAL_DOWNLOADS_REPORT_ID;
import static org.dspace.app.rest.utils.UsageReportUtils.TOTAL_VISITS_PER_MONTH_REPORT_ID;
import static org.dspace.app.rest.utils.UsageReportUtils.TOTAL_VISITS_REPORT_ID;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.matcher.UsageReportMatcher;
import org.dspace.app.rest.model.UsageReportPointCityRest;
import org.dspace.app.rest.model.UsageReportPointCountryRest;
import org.dspace.app.rest.model.UsageReportPointDateRest;
import org.dspace.app.rest.model.UsageReportPointDsoTotalVisitsRest;
import org.dspace.app.rest.model.UsageReportPointRest;
import org.dspace.app.rest.model.ViewEventRest;
import org.dspace.app.rest.repository.StatisticsRestRepository;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.rest.utils.UsageReportUtils;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.ResourcePolicyBuilder;
import org.dspace.builder.SiteBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.Site;
import org.dspace.core.Constants;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.dspace.statistics.factory.StatisticsServiceFactory;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Integration test to test the /api/statistics/usagereports/ endpoints, see {@link UsageReportUtils} and
 * {@link StatisticsRestRepository}
 *
 * @author Maria Verdonck (Atmire) on 10/06/2020
 */
public class StatisticsRestRepositoryIT extends AbstractControllerIntegrationTest {

    @Autowired
    ConfigurationService configurationService;
    @Autowired
    protected AuthorizeService authorizeService;

    private Community communityNotVisited;
    private Community communityVisited;
    private Collection collectionNotVisited;
    private Collection collectionVisited;
    private Item itemNotVisitedWithBitstreams;
    private Item itemVisited;
    private Bitstream bitstreamNotVisited;
    private Bitstream bitstreamVisited;

    private String loggedInToken;
    private String adminToken;

    @BeforeClass
    public static void clearStatistics() throws Exception {
        // To ensure these tests start "fresh", clear out any existing statistics data.
        // NOTE: this is committed immediately in removeIndex()
        StatisticsServiceFactory.getInstance().getSolrLoggerService().removeIndex("*:*");
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Explicitly use solr commit in SolrLoggerServiceImpl#postView
        configurationService.setProperty("solr-statistics.autoCommit", false);
        configurationService.setProperty("usage-statistics.authorization.admin.usage", true);

        context.turnOffAuthorisationSystem();

        Community community = CommunityBuilder.createCommunity(context).build();
        communityNotVisited = CommunityBuilder.createSubCommunity(context, community).build();
        communityVisited = CommunityBuilder.createSubCommunity(context, community).build();
        collectionNotVisited = CollectionBuilder.createCollection(context, community).build();
        collectionVisited = CollectionBuilder.createCollection(context, community).build();
        itemVisited = ItemBuilder.createItem(context, collectionNotVisited).build();
        itemNotVisitedWithBitstreams = ItemBuilder.createItem(context, collectionNotVisited).build();
        bitstreamNotVisited = BitstreamBuilder.createBitstream(context,
            itemNotVisitedWithBitstreams, toInputStream("test", UTF_8)).withName("BitstreamNotVisitedName").build();
        bitstreamVisited = BitstreamBuilder
            .createBitstream(context, itemNotVisitedWithBitstreams, toInputStream("test", UTF_8))
            .withName("BitstreamVisitedName").build();

        loggedInToken = getAuthToken(eperson.getEmail(), password);
        adminToken = getAuthToken(admin.getEmail(), password);

        context.restoreAuthSystemState();
    }

    @Test
    public void usagereports_withoutId_NotImplementedException() throws Exception {
        getClient().perform(get("/api/statistics/usagereports"))
                   .andExpect(status().is(HttpStatus.METHOD_NOT_ALLOWED.value()));
    }

    @Test
    public void usagereports_notProperUUIDAndReportId_Exception() throws Exception {
        getClient(adminToken).perform(get("/api/statistics/usagereports/notProperUUIDAndReportId"))
                   .andExpect(status().isNotFound());
    }

    @Test
    public void usagereports_nonValidUUIDpart_Exception() throws Exception {
        getClient(adminToken).perform(get("/api/statistics/usagereports/notAnUUID" + "_" + TOTAL_VISITS_REPORT_ID))
                   .andExpect(status().isNotFound());
    }

    @Test
    public void usagereports_nonValidReportIDpart_Exception() throws Exception {
        getClient(adminToken).perform(get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() +
                                "_NotValidReport"))
                   .andExpect(status().is(HttpStatus.NOT_FOUND.value()));
    }

    @Test
    public void usagereports_nonValidReportIDpart_Exception_By_Anonymous_Unauthorized_Test() throws Exception {
        getClient().perform(get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() +
                                "_NotValidReport"))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void usagereports_nonValidReportIDpart_Exception_By_Anonymous_Test() throws Exception {
        configurationService.setProperty("usage-statistics.authorization.admin.usage", false);
        getClient().perform(get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() +
                                "_NotValidReport"))
                   .andExpect(status().isNotFound());
    }

    @Test
    public void usagereports_NonExistentUUID_Exception() throws Exception {
        getClient(adminToken).perform(
                  get("/api/statistics/usagereports/" + UUID.randomUUID() + "_" + TOTAL_VISITS_REPORT_ID))
                   .andExpect(status().is(HttpStatus.NOT_FOUND.value()));
    }

    @Test
    public void usagereport_onlyAdminReadRights() throws Exception {
        // ** WHEN **
        authorizeService.removeAllPolicies(context, itemNotVisitedWithBitstreams);
        // We request a dso's TotalVisits usage stat report as anon but dso has no read policy for anon
        getClient().perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isUnauthorized());
        // We request a dso's TotalVisits usage stat report as admin
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                             // ** THEN **
                             .andExpect(status().isOk());
    }

    @Test
    public void usagereport_onlyAdminReadRights_unvalidToken() throws Exception {
        // ** WHEN **
        authorizeService.removeAllPolicies(context, itemNotVisitedWithBitstreams);
        // We request a dso's TotalVisits usage stat report with unvalid token
        getClient("unvalidToken").perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                                 // ** THEN **
                                 .andExpect(status().isUnauthorized());
    }

    @Test
    public void usagereport_loggedInUserReadRights() throws Exception {
        // ** WHEN **
        context.turnOffAuthorisationSystem();
        authorizeService.removeAllPolicies(context, itemNotVisitedWithBitstreams);
        ResourcePolicyBuilder.createResourcePolicy(context, eperson, null)
                             .withDspaceObject(itemNotVisitedWithBitstreams)
                             .withAction(Constants.READ).build();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                                         .withEmail("eperson1@mail.com")
                                         .withPassword(password)
                                         .build();
        context.restoreAuthSystemState();
        String anotherLoggedInUserToken = getAuthToken(eperson1.getEmail(), password);
        // We request a dso's TotalVisits usage stat report as anon but dso has no read policy for anon
        getClient().perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isUnauthorized());
        // We request a dso's TotalVisits usage stat report as logged in eperson and has read policy for this user
        getClient(loggedInToken).perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                                // ** THEN **
                                .andExpect(status().isForbidden());
        // We request a dso's TotalVisits usage stat report as another logged in eperson and has no read policy for
        // this user
        getClient(anotherLoggedInUserToken).perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                                           // ** THEN **
                                           .andExpect(status().isForbidden());
    }

    @Test
    public void usagereport_loggedInUserReadRights_and_usage_statistics_admin_is_false_Test() throws Exception {
        configurationService.setProperty("usage-statistics.authorization.admin.usage", false);
        context.turnOffAuthorisationSystem();
        authorizeService.removeAllPolicies(context, itemNotVisitedWithBitstreams);
        ResourcePolicyBuilder.createResourcePolicy(context, eperson, null)
                             .withDspaceObject(itemNotVisitedWithBitstreams)
                             .withAction(Constants.READ).build();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                                         .withEmail("eperson1@mail.com")
                                         .withPassword(password)
                                         .build();
        context.restoreAuthSystemState();
        String anotherLoggedInUserToken = getAuthToken(eperson1.getEmail(), password);
        // We request a dso's TotalVisits usage stat report as anon but dso has no read policy for anon
        getClient().perform(get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" +
                                TOTAL_VISITS_REPORT_ID))
                   .andExpect(status().isUnauthorized());

        // We request a dso's TotalVisits usage stat report as logged in eperson and has read policy for this user
        getClient(loggedInToken).perform(get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() +
                                             "_" + TOTAL_VISITS_REPORT_ID))
                                .andExpect(status().isOk());

        // We request a dso's TotalVisits usage stat report as another logged
        // in eperson and has no read policy for this user
        getClient(anotherLoggedInUserToken).perform(
             get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                                           .andExpect(status().isForbidden());
    }

    @Test
    public void totalVisitsReport_Community_Visited() throws Exception {
        // ** WHEN **
        // We visit the community
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("community");
        viewEventRest.setTargetId(communityVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        // And request that community's TotalVisits stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + communityVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           communityVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                           TOTAL_VISITS_REPORT_ID,
                           List.of(
                               getExpectedDsoViews(communityVisited, 1)
                           )
                       )
                   )));
    }

    @Test
    public void totalVisitsReport_Community_NotVisited() throws Exception {
        // ** WHEN **
        // Community is never visited
        // And request that community's TotalVisits stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + communityNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           communityNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                           TOTAL_VISITS_REPORT_ID,
                           List.of(
                               getExpectedDsoViews(communityNotVisited, 0)
                           )
                       )
                   )));
    }

    @Test
    public void totalVisitsReport_Collection_Visited() throws Exception {
        // ** WHEN **
        // We visit the collection twice
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("collection");
        viewEventRest.setTargetId(collectionVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        // And request that collection's TotalVisits stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           collectionVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                           TOTAL_VISITS_REPORT_ID,
                           List.of(
                               getExpectedDsoViews(collectionVisited, 2)
                           )
                       )
                   )));
    }

    @Test
    public void totalVisitsReport_Collection_NotVisited() throws Exception {
        // ** WHEN **
        // Collection is never visited
        // And request that collection's TotalVisits stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + collectionNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           collectionNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                           TOTAL_VISITS_REPORT_ID,
                           List.of(
                               getExpectedDsoViews(collectionNotVisited, 0)
                           )
                       )
                   )));
    }

    @Test
    public void totalVisitsReport_Item_Visited() throws Exception {
        // ** WHEN **
        // We visit an Item
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("item");
        viewEventRest.setTargetId(itemVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        // And request that collection's TotalVisits stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           itemVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                           TOTAL_VISITS_REPORT_ID,
                           List.of(
                               getExpectedDsoViews(itemVisited, 1)
                           )
                       )
                   )));
    }

    @Test
    public void totalVisitsReport_Item_NotVisited() throws Exception {
        // ** WHEN **
        //Item is never visited
        List<UsageReportPointRest> expectedPoints = List.of(
            getExpectedDsoViews(itemNotVisitedWithBitstreams, 0)
        );

        // And request that item's TotalVisits stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(
                                 UsageReportMatcher.matchUsageReport(
                                     itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                                     TOTAL_VISITS_REPORT_ID,
                                     expectedPoints
                                 )
                             )));

        // only admin access visits report
        getClient(loggedInToken).perform(
             get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
            .andExpect(status().isForbidden());

        getClient().perform(
             get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
            .andExpect(status().isUnauthorized());

        // make statistics visible to all
        configurationService.setProperty("usage-statistics.authorization.admin.usage", false);

        getClient(loggedInToken).perform(
                get("/api/statistics/usagereports/"
                        + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                        TOTAL_VISITS_REPORT_ID,
                        expectedPoints
                    )
                )));

       getClient().perform(
                get("/api/statistics/usagereports/"
                        + itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                        TOTAL_VISITS_REPORT_ID,
                        expectedPoints
                    )
                )));
    }

    @Test
    public void totalVisitsReport_Bitstream_Visited() throws Exception {
        // ** WHEN **
        // We visit a Bitstream
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("bitstream");
        viewEventRest.setTargetId(bitstreamVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        List<UsageReportPointRest> expectedPoints = List.of(
            getExpectedDsoViews(bitstreamVisited, 1)
        );

        // And request that bitstream's TotalVisits stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           bitstreamVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                           TOTAL_VISITS_REPORT_ID,
                           expectedPoints
                       )
                   )));

        // only admin access visits report
        getClient(loggedInToken).perform(
                  get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                 .andExpect(status().isForbidden());

        getClient().perform(
                  get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                 .andExpect(status().isUnauthorized());

        // make statistics visible to all
        configurationService.setProperty("usage-statistics.authorization.admin.usage", false);

        getClient(loggedInToken).perform(
                get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        bitstreamVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                        TOTAL_VISITS_REPORT_ID,
                        expectedPoints
                    )
                )));

        getClient().perform(
                get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        bitstreamVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                        TOTAL_VISITS_REPORT_ID,
                        expectedPoints
                    )
                )));
    }

    @Test
    public void totalVisitsReport_Bitstream_NotVisited() throws Exception {
        // ** WHEN **
        // Bitstream is never visited

        List<UsageReportPointRest> expectedPoints = List.of(
            getExpectedDsoViews(bitstreamNotVisited, 0)
        );

        String authToken = getAuthToken(admin.getEmail(), password);
        // And request that bitstream's TotalVisits stat report
        getClient(authToken).perform(
            get("/api/statistics/usagereports/" + bitstreamNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           bitstreamNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                           TOTAL_VISITS_REPORT_ID,
                           expectedPoints
                       )
                   )));

        String tokenEPerson = getAuthToken(eperson.getEmail(), password);
        getClient(tokenEPerson).perform(
                  get("/api/statistics/usagereports/" + bitstreamNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                 .andExpect(status().isForbidden());

        getClient().perform(
                    get("/api/statistics/usagereports/" + bitstreamNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                   .andExpect(status().isUnauthorized());

        // make statistics visible to all
        configurationService.setProperty("usage-statistics.authorization.admin.usage", false);

        getClient(tokenEPerson).perform(
                get("/api/statistics/usagereports/" + bitstreamNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        bitstreamNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                        TOTAL_VISITS_REPORT_ID,
                        expectedPoints
                    )
                )));

      getClient().perform(
                get("/api/statistics/usagereports/" + bitstreamNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        bitstreamNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                        TOTAL_VISITS_REPORT_ID,
                        expectedPoints
                    )
                )));
    }

    @Test
    public void totalVisitsPerMonthReport_Item_Visited() throws Exception {
        // ** WHEN **
        // We visit an Item
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("item");
        viewEventRest.setTargetId(itemVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        List<UsageReportPointRest> expectedPoints = this.getListOfVisitsPerMonthsPoints(1);

        // And request that item's TotalVisitsPerMonth stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                           TOTAL_VISITS_PER_MONTH_REPORT_ID,
                           expectedPoints
                       )
                   )));

        // only admin has access
        getClient(loggedInToken).perform(
                 get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID))
                .andExpect(status().isForbidden());

        getClient().perform(
                 get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID))
                .andExpect(status().isUnauthorized());

        // make statistics visible to all
        configurationService.setProperty("usage-statistics.authorization.admin.usage", false);

        getClient(loggedInToken).perform(
                get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                        TOTAL_VISITS_PER_MONTH_REPORT_ID,
                        expectedPoints
                        )
                )));

       getClient().perform(
                get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                        TOTAL_VISITS_PER_MONTH_REPORT_ID,
                        expectedPoints
                    )
                )));
    }

    @Test
    public void totalVisitsPerMonthReport_Item_NotVisited() throws Exception {
        // ** WHEN **
        // Item is not visited
        // And request that item's TotalVisitsPerMonth stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" +
                TOTAL_VISITS_PER_MONTH_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                               itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                               TOTAL_VISITS_PER_MONTH_REPORT_ID,
                               this.getListOfVisitsPerMonthsPoints(0)
                       )
                   )));
    }

    @Test
    public void totalVisitsPerMonthReport_Collection_Visited() throws Exception {
        // ** WHEN **
        // We visit a Collection twice
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("collection");
        viewEventRest.setTargetId(collectionVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        // And request that collection's TotalVisitsPerMonth stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           collectionVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                           TOTAL_VISITS_PER_MONTH_REPORT_ID,
                           this.getListOfVisitsPerMonthsPoints(2)
                       )
                   )));
    }

    @Test
    public void TotalDownloadsReport_Bitstream() throws Exception {
        // ** WHEN **
        // We visit a Bitstream
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("bitstream");
        viewEventRest.setTargetId(bitstreamVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        List<UsageReportPointRest> expectedPoints = List.of(
            getExpectedDsoViews(bitstreamVisited, 1)
        );

        // And request that bitstreams's TotalDownloads stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           bitstreamVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID,
                           TOTAL_DOWNLOADS_REPORT_ID,
                           expectedPoints
                       )
                   )));

        // only admin has access to downloads report
        getClient(loggedInToken).perform(
                  get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID))
                 .andExpect(status().isForbidden());

        getClient().perform(
                  get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID))
                 .andExpect(status().isUnauthorized());

        // make statistics visible to all
        configurationService.setProperty("usage-statistics.authorization.admin.usage", false);

        getClient(loggedInToken).perform(
                get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        bitstreamVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID,
                        TOTAL_DOWNLOADS_REPORT_ID,
                        expectedPoints
                    )
                )));

        getClient().perform(
                get("/api/statistics/usagereports/" + bitstreamVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        bitstreamVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID,
                        TOTAL_DOWNLOADS_REPORT_ID,
                        expectedPoints
                    )
                )));
    }

    @Test
    public void TotalDownloadsReport_Item() throws Exception {
        // ** WHEN **
        // We visit an Item's bitstream
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("bitstream");
        viewEventRest.setTargetId(bitstreamVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        // And request that item's TotalDownloads stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" +
                TOTAL_DOWNLOADS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID,
                           TOTAL_DOWNLOADS_REPORT_ID,
                           List.of(
                               getExpectedDsoViews(bitstreamVisited, 1)
                           )
                       )
                   )));
    }

    @Test
    public void TotalDownloadsReport_Item_NotVisited() throws Exception {
        // ** WHEN **
        // You don't visit an item's bitstreams
        // And request that item's TotalDownloads stat report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" +
                TOTAL_DOWNLOADS_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           itemNotVisitedWithBitstreams.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID,
                           TOTAL_DOWNLOADS_REPORT_ID,
                           List.of()
                       )
                   )));
    }

    @Test
    public void TotalDownloadsReport_Item_ReturnsAllBitstreamsWhenLimitNotPositive() throws Exception {
        // ** GIVEN **
        // The File visits (TotalDownloads) report is configured without a positive limit, so every downloaded
        // bitstream should be returned and the UI can paginate through all files (see issue #807).
        configurationService.setProperty("usage-statistics.topDownloadsLimit", 0);

        int numberOfBitstreams = 12;
        context.turnOffAuthorisationSystem();
        Item item = ItemBuilder.createItem(context, collectionNotVisited)
                               .withTitle("Item with many files").build();
        List<Bitstream> bitstreams = new ArrayList<>();
        for (int i = 0; i < numberOfBitstreams; i++) {
            bitstreams.add(BitstreamBuilder.createBitstream(context, item, toInputStream("test", UTF_8))
                                           .withName("Bitstream " + i).build());
        }
        context.restoreAuthSystemState();

        // ** WHEN **
        // We register a download (bitstream view) for every bitstream, so each shows up with 1 view.
        ObjectMapper mapper = new ObjectMapper();
        List<UsageReportPointRest> expectedPoints = new ArrayList<>();
        for (Bitstream bitstream : bitstreams) {
            ViewEventRest viewEventRest = new ViewEventRest();
            viewEventRest.setTargetType("bitstream");
            viewEventRest.setTargetId(bitstream.getID());
            getClient(loggedInToken).perform(post("/api/statistics/viewevents")
                .content(mapper.writeValueAsBytes(viewEventRest))
                .contentType(contentType))
                                    .andExpect(status().isCreated());
            expectedPoints.add(getExpectedDsoViews(bitstream, 1));
        }

        // ** THEN **
        // The TotalDownloads report contains a point for every downloaded bitstream (not just the legacy first 10).
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + item.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(
                                 UsageReportMatcher.matchUsageReport(
                                     item.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID,
                                     TOTAL_DOWNLOADS_REPORT_ID,
                                     expectedPoints
                                 )
                             )));
    }

    @Test
    public void TotalDownloadsReport_Item_AppliesConfiguredCap() throws Exception {
        // ** GIVEN **
        // A positive limit is configured, so the File visits report is capped at that many bitstream rows.
        configurationService.setProperty("usage-statistics.topDownloadsLimit", 5);

        int numberOfBitstreams = 12;
        context.turnOffAuthorisationSystem();
        Item item = ItemBuilder.createItem(context, collectionNotVisited)
                               .withTitle("Item with capped files").build();
        List<Bitstream> bitstreams = new ArrayList<>();
        for (int i = 0; i < numberOfBitstreams; i++) {
            bitstreams.add(BitstreamBuilder.createBitstream(context, item, toInputStream("test", UTF_8))
                                           .withName("Bitstream " + i).build());
        }
        context.restoreAuthSystemState();

        // ** WHEN **
        ObjectMapper mapper = new ObjectMapper();
        for (Bitstream bitstream : bitstreams) {
            ViewEventRest viewEventRest = new ViewEventRest();
            viewEventRest.setTargetType("bitstream");
            viewEventRest.setTargetId(bitstream.getID());
            getClient(loggedInToken).perform(post("/api/statistics/viewevents")
                .content(mapper.writeValueAsBytes(viewEventRest))
                .contentType(contentType))
                                    .andExpect(status().isCreated());
        }

        // ** THEN **
        // Exactly the configured number of rows is returned. Which 5 (all tie at 1 view) is facet ordering and
        // not part of the contract, so we only assert the size.
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + item.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$.points", Matchers.hasSize(5)));
    }

    @Test
    public void TotalVisitsPerMonthReport_Item_WindowIsConfigurable() throws Exception {
        // ** GIVEN **
        // The month window controls how far back the "Total visits per month" report reaches; widening it lets
        // users browse (and the UI paginate through) older months and years (see issue #807).
        // NOTE: DSpace ITs do not reset configuration between tests, and resolveTotalVisitsPerMonth reads this
        // property as a String; we therefore set it as a String and restore the original value in a finally block
        // so this test never leaks a modified window into the month-report assertions of other tests.
        String originalStartDateInterval =
            configurationService.getProperty("usage-statistics.startDateInterval");
        configurationService.setProperty("usage-statistics.startDateInterval", "-11");
        try {
            // ** WHEN **
            // We visit an item once.
            ViewEventRest viewEventRest = new ViewEventRest();
            viewEventRest.setTargetType("item");
            viewEventRest.setTargetId(itemVisited.getID());
            ObjectMapper mapper = new ObjectMapper();
            getClient().perform(post("/api/statistics/viewevents")
                .content(mapper.writeValueAsBytes(viewEventRest))
                .contentType(contentType))
                       .andExpect(status().isCreated());

            // ** THEN **
            // The report returns one point per month across the configured window (11 months back + current = 12),
            // with the current month holding the single view.
            getClient(adminToken).perform(
                get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID))
                                 .andExpect(status().isOk())
                                 .andExpect(jsonPath("$.points", Matchers.hasSize(12)))
                                 .andExpect(jsonPath("$", Matchers.is(
                                     UsageReportMatcher.matchUsageReport(
                                         itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                                         TOTAL_VISITS_PER_MONTH_REPORT_ID,
                                         getListOfVisitsPerMonthsPoints(1)
                                     )
                                 )));
        } finally {
            configurationService.setProperty("usage-statistics.startDateInterval", originalStartDateInterval);
        }
    }

    @Test
    public void TotalDownloadsReport_NotSupportedDSO_Collection() throws Exception {
        getClient(adminToken)
            .perform(get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID))
            .andExpect(status().isNotFound());
    }

    /**
     * Note: Geolite response mocked in {@link org.dspace.statistics.MockSolrLoggerServiceImpl}, which returns the
     * same country/city for every request, so distinct rows cannot be seeded here. This guards that a non-positive
     * ("unlimited") limit still produces a valid report — the ">100 rows" behaviour is exercised by the site-level
     * {@link #usageReportsSearch_Site_ReturnsAllItemsWhenLimitNotPositive} test on the same Solr facet path.
     */
    @Test
    public void topCountriesReport_Collection_LimitNotPositive_StillReturnsResults() throws Exception {
        configurationService.setProperty("usage-statistics.topCountriesLimit", 0);

        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("collection");
        viewEventRest.setTargetId(collectionVisited.getID());
        ObjectMapper mapper = new ObjectMapper();
        for (int i = 0; i < 2; i++) {
            getClient(loggedInToken).perform(post("/api/statistics/viewevents")
                .content(mapper.writeValueAsBytes(viewEventRest))
                .contentType(contentType))
                                    .andExpect(status().isCreated());
        }

        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(
                                 UsageReportMatcher.matchUsageReport(
                                     collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                                     TOP_COUNTRIES_REPORT_ID,
                                     List.of(getExpectedCountryViews("US", "United States", 2))
                                 )
                             )));
    }

    /**
     * Note: Geolite response mocked in {@link org.dspace.statistics.MockSolrLoggerServiceImpl}. See the country
     * counterpart above for why only the "unlimited limit still works" behaviour is asserted here.
     */
    @Test
    public void topCitiesReport_Collection_LimitNotPositive_StillReturnsResults() throws Exception {
        configurationService.setProperty("usage-statistics.topCitiesLimit", 0);

        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("collection");
        viewEventRest.setTargetId(collectionVisited.getID());
        ObjectMapper mapper = new ObjectMapper();
        for (int i = 0; i < 2; i++) {
            getClient(loggedInToken).perform(post("/api/statistics/viewevents")
                .content(mapper.writeValueAsBytes(viewEventRest))
                .contentType(contentType))
                                    .andExpect(status().isCreated());
        }

        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOP_CITIES_REPORT_ID))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(
                                 UsageReportMatcher.matchUsageReport(
                                     collectionVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                                     TOP_CITIES_REPORT_ID,
                                     List.of(getExpectedCityViews("New York", 2))
                                 )
                             )));
    }

    /**
     * Note: Geolite response mocked in {@link org.dspace.statistics.MockSolrLoggerServiceImpl}
     */
    @Test
    public void topCountriesReport_Collection_Visited() throws Exception {
        // ** WHEN **
        // We visit a Collection
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("collection");
        viewEventRest.setTargetId(collectionVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        List<UsageReportPointRest> expectedPoints = List.of(
            getExpectedCountryViews("US", "United States", 1)
        );

        // And request that collection's TopCountries report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                           TOP_COUNTRIES_REPORT_ID,
                           expectedPoints
                       )
                   )));

        // only admin has access to countries report
        getClient(loggedInToken).perform(
                  get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID))
                 .andExpect(status().isForbidden());

        getClient().perform(
                  get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID))
                 .andExpect(status().isUnauthorized());

        // make statistics visible to all
        configurationService.setProperty("usage-statistics.authorization.admin.usage", false);

        getClient(loggedInToken).perform(
                get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                        TOP_COUNTRIES_REPORT_ID,
                        expectedPoints
                        )
                )));

      getClient().perform(
                get("/api/statistics/usagereports/" + collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        collectionVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                        TOP_COUNTRIES_REPORT_ID,
                        expectedPoints
                    )
                )));
    }

    /**
     * Note: Geolite response mocked in {@link org.dspace.statistics.MockSolrLoggerServiceImpl}
     */
    @Test
    public void topCountriesReport_Community_Visited() throws Exception {
        // ** WHEN **
        // We visit a Community twice
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("community");
        viewEventRest.setTargetId(communityVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        // And request that collection's TopCountries report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + communityVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           communityVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                           TOP_COUNTRIES_REPORT_ID,
                           List.of(
                               getExpectedCountryViews("US", "United States", 2)
                           )
                       )
                   )));
    }

    /**
     * Note: Geolite response mocked in {@link org.dspace.statistics.MockSolrLoggerServiceImpl}
     */
    @Test
    public void topCountriesReport_Item_NotVisited() throws Exception {
        // ** WHEN **
        // Item is not visited
        // And request that item's TopCountries report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + itemNotVisitedWithBitstreams.getID() + "_" + TOP_COUNTRIES_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           itemNotVisitedWithBitstreams.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                           TOP_COUNTRIES_REPORT_ID,
                           List.of()
                       )
                   )));
    }

    /**
     * Note: Geolite response mocked in {@link org.dspace.statistics.MockSolrLoggerServiceImpl}
     */
    @Test
    public void topCitiesReport_Item_Visited() throws Exception {
        // ** WHEN **
        // We visit an Item
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("item");
        viewEventRest.setTargetId(itemVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        List<UsageReportPointRest> expectedPoints = List.of(
            getExpectedCityViews("New York", 1)
        );

        // And request that item's TopCities report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                           TOP_CITIES_REPORT_ID,
                           expectedPoints
                       )
                   )));

        // only admin has access to cities report
        getClient(loggedInToken).perform(
                  get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID))
                 .andExpect(status().isForbidden());

        getClient().perform(
                  get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID))
                 .andExpect(status().isUnauthorized());

        // make statistics visible to all
        configurationService.setProperty("usage-statistics.authorization.admin.usage", false);

        getClient(loggedInToken).perform(
                get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                        TOP_CITIES_REPORT_ID,
                        expectedPoints
                    )
                )));

        getClient().perform(
                get("/api/statistics/usagereports/" + itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(
                    UsageReportMatcher.matchUsageReport(
                        itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                        TOP_CITIES_REPORT_ID,
                        expectedPoints
                    )
                )));
    }

    /**
     * Note: Geolite response mocked in {@link org.dspace.statistics.MockSolrLoggerServiceImpl}
     */
    @Test
    public void topCitiesReport_Community_Visited() throws Exception {
        // ** WHEN **
        // We visit a Community thrice
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("community");
        viewEventRest.setTargetId(communityVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        getClient(loggedInToken).perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                                .andExpect(status().isCreated());

        // And request that community's TopCities report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + communityVisited.getID() + "_" + TOP_CITIES_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           communityVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                           TOP_CITIES_REPORT_ID,
                           List.of(
                               getExpectedCityViews("New York", 3)
                           )
                       )
                   )));
    }

    /**
     * Note: Geolite response mocked in {@link org.dspace.statistics.MockSolrLoggerServiceImpl}
     */
    @Test
    public void topCitiesReport_Collection_NotVisited() throws Exception {
        // ** WHEN **
        // Collection is not visited
        // And request that collection's TopCountries report
        getClient(adminToken).perform(
            get("/api/statistics/usagereports/" + collectionNotVisited.getID() + "_" + TOP_CITIES_REPORT_ID))
                   // ** THEN **
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(
                       UsageReportMatcher.matchUsageReport(
                           collectionNotVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                           TOP_CITIES_REPORT_ID,
                           List.of()
                       )
                   )));
    }

    @Test
    public void usagereportsSearch_notProperURI_Exception() throws Exception {
        getClient(adminToken).perform(get("/api/statistics/usagereports/search/object?uri=BadUri"))
                   .andExpect(status().is(HttpStatus.BAD_REQUEST.value()));
    }

    @Test
    public void usagereportsSearch_noURI_Exception() throws Exception {
        getClient().perform(get("/api/statistics/usagereports/search/object"))
                   .andExpect(status().is(HttpStatus.BAD_REQUEST.value()));
    }

    @Test
    public void usagereportsSearch_NonExistentUUID_Exception() throws Exception {
        getClient(adminToken).perform(
                  get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                                "/items/" + UUID.randomUUID()))
                   .andExpect(status().is(HttpStatus.NOT_FOUND.value()));
    }

    @Test
    public void usagereportSearch_onlyAdminReadRights() throws Exception {
        // ** WHEN **
        authorizeService.removeAllPolicies(context, itemNotVisitedWithBitstreams);
        // We request a dso's TotalVisits usage stat report as anon but dso has no read policy for anon
        getClient().perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                                "/items/" + itemNotVisitedWithBitstreams.getID()))
                   // ** THEN **
                   .andExpect(status().isUnauthorized());
        // We request a dso's TotalVisits usage stat report as admin
        getClient(adminToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api" +
                         "/core/items/" + itemNotVisitedWithBitstreams.getID()))
            // ** THEN **
            .andExpect(status().isOk());
    }

    @Test
    public void usagereportSearch_onlyAdminReadRights_unvalidToken() throws Exception {
        // ** WHEN **
        authorizeService.removeAllPolicies(context, itemNotVisitedWithBitstreams);
        // We request a dso's TotalVisits usage stat report with unvalid token
        getClient("unvalidToken")
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/items/" + itemNotVisitedWithBitstreams.getID()))
            // ** THEN **
            .andExpect(status().isUnauthorized());
    }

    @Test
    public void usagereportSearch_loggedInUserReadRights() throws Exception {
        // ** WHEN **
        context.turnOffAuthorisationSystem();
        authorizeService.removeAllPolicies(context, itemNotVisitedWithBitstreams);
        ResourcePolicyBuilder.createResourcePolicy(context, eperson, null)
                             .withDspaceObject(itemNotVisitedWithBitstreams)
                             .withAction(Constants.READ).build();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                                         .withEmail("eperson1@mail.com")
                                         .withPassword(password)
                                         .build();
        context.restoreAuthSystemState();
        String anotherLoggedInUserToken = getAuthToken(eperson1.getEmail(), password);
        // We request a dso's TotalVisits usage stat report as anon but dso has no read policy for anon
        getClient()
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/items/" + itemNotVisitedWithBitstreams.getID()))
            // ** THEN **
            .andExpect(status().isUnauthorized());
        // We request a dso's TotalVisits usage stat report as logged in eperson and has read policy for this user
        getClient(loggedInToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/items/" + itemNotVisitedWithBitstreams.getID()))
            // ** THEN **
            .andExpect(status().isForbidden());
        // We request a dso's TotalVisits usage stat report as another logged in eperson and has no read policy for
        // this user
        getClient(anotherLoggedInUserToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/items/" + itemNotVisitedWithBitstreams.getID()))
            // ** THEN **
            .andExpect(status().isForbidden());
    }

    @Test
    public void usageReportsSearch_Site() throws Exception {
        context.turnOffAuthorisationSystem();
        Site site = SiteBuilder.createSite(context).build();
        Item itemVisited2 = ItemBuilder.createItem(context, collectionNotVisited).build();
        context.restoreAuthSystemState();

        // ** WHEN **
        // We visit an item and another twice
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("item");
        viewEventRest.setTargetId(itemVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                   .andExpect(status().isCreated());

        ViewEventRest viewEventRest2 = new ViewEventRest();
        viewEventRest2.setTargetType("item");
        viewEventRest2.setTargetId(itemVisited2.getID());

        ObjectMapper mapper2 = new ObjectMapper();

        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper2.writeValueAsBytes(viewEventRest2))
            .contentType(contentType))
                   .andExpect(status().isCreated());

        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper2.writeValueAsBytes(viewEventRest2))
            .contentType(contentType))
                   .andExpect(status().isCreated());

        // And request the sites global usage report (show top most popular items)
        getClient(adminToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/sites/" + site.getID()))
            // ** THEN **
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.usagereports", not(empty())))
            .andExpect(jsonPath("$._embedded.usagereports", Matchers.containsInAnyOrder(
                UsageReportMatcher.matchUsageReport(
                    site.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                    TOTAL_VISITS_REPORT_ID,
                    List.of(
                        getExpectedDsoViews(itemVisited, 1),
                        getExpectedDsoViews(itemVisited2, 2)
                    )
                )
            )));
    }

    @Test
    public void usageReportsSearch_Site_ReturnsAllItemsWhenLimitNotPositive() throws Exception {
        // ** GIVEN **
        // The repository wide usage report is configured without a positive top-items limit, meaning every
        // visited item should be returned so the UI can paginate through all datasets (see issue #726).
        configurationService.setProperty("usage-statistics.topItemsLimit", 0);

        int numberOfItems = 12;
        context.turnOffAuthorisationSystem();
        Site site = SiteBuilder.createSite(context).build();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < numberOfItems; i++) {
            items.add(ItemBuilder.createItem(context, collectionNotVisited)
                                 .withTitle("Statistics pagination item " + i).build());
        }
        context.restoreAuthSystemState();

        // ** WHEN **
        // We register a view event for every item, so each one shows up in the global report with 1 view.
        ObjectMapper mapper = new ObjectMapper();
        List<UsageReportPointRest> expectedPoints = new ArrayList<>();
        for (Item item : items) {
            ViewEventRest viewEventRest = new ViewEventRest();
            viewEventRest.setTargetType("item");
            viewEventRest.setTargetId(item.getID());
            getClient().perform(post("/api/statistics/viewevents")
                .content(mapper.writeValueAsBytes(viewEventRest))
                .contentType(contentType))
                       .andExpect(status().isCreated());
            expectedPoints.add(getExpectedDsoViews(item, 1));
        }

        // ** THEN **
        // The global TotalVisits report contains a point for every visited item (not just the legacy first 10),
        // and each point matches the expected item (id/label/views), regardless of ordering.
        getClient(adminToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/sites/" + site.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.usagereports", Matchers.containsInAnyOrder(
                UsageReportMatcher.matchUsageReport(
                    site.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                    TOTAL_VISITS_REPORT_ID,
                    expectedPoints
                )
            )));
    }

    @Test
    public void usageReportsSearch_Community_Visited() throws Exception {
        // ** WHEN **
        // We visit a community
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("community");
        viewEventRest.setTargetId(communityVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                   .andExpect(status().isCreated());

        // And request the community usage reports
        getClient(adminToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/communities/" + communityVisited.getID()))
            // ** THEN **
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.usagereports", not(empty())))
            .andExpect(jsonPath("$._embedded.usagereports", Matchers.containsInAnyOrder(
                UsageReportMatcher.matchUsageReport(
                    communityVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                    TOTAL_VISITS_REPORT_ID,
                    List.of(
                        getExpectedDsoViews(communityVisited, 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    communityVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    this.getListOfVisitsPerMonthsPoints(1)
                ),
                UsageReportMatcher.matchUsageReport(
                    communityVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                    TOP_CITIES_REPORT_ID,
                    List.of(
                        getExpectedCityViews("New York", 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    communityVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                    TOP_COUNTRIES_REPORT_ID,
                    List.of(
                        getExpectedCountryViews("US", "United States", 1)
                    )
                )
            )));
    }

    @Test
    public void usageReportsSearch_Collection_NotVisited() throws Exception {
        // ** WHEN **
        // Collection is not visited
        // And request the collection's usage reports
        getClient(adminToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/collections/" + collectionNotVisited.getID()))
            // ** THEN **
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.usagereports", not(empty())))
            .andExpect(jsonPath("$._embedded.usagereports", Matchers.containsInAnyOrder(
                UsageReportMatcher.matchUsageReport(
                    collectionNotVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                    TOTAL_VISITS_REPORT_ID,
                    List.of(
                        getExpectedDsoViews(collectionNotVisited, 0)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    collectionNotVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    this.getListOfVisitsPerMonthsPoints(0)
                ),
                UsageReportMatcher.matchUsageReport(
                    collectionNotVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                    TOP_CITIES_REPORT_ID,
                    List.of()
                ),
                UsageReportMatcher.matchUsageReport(
                    collectionNotVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                    TOP_COUNTRIES_REPORT_ID,
                    List.of()
                )
            )));
    }

    @Test
    public void usageReportsSearch_Item_Visited_FileNotVisited() throws Exception {
        // ** WHEN **
        // We visit an item
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("item");
        viewEventRest.setTargetId(itemVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                   .andExpect(status().isCreated());

        // And request the community usage reports
        getClient(adminToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/items/" + itemVisited.getID()))
            // ** THEN **
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.usagereports", not(empty())))
            .andExpect(jsonPath("$._embedded.usagereports", Matchers.containsInAnyOrder(
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                    TOTAL_VISITS_REPORT_ID,
                    List.of(
                        getExpectedDsoViews(itemVisited, 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    this.getListOfVisitsPerMonthsPoints(1)
                ),
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                    TOP_CITIES_REPORT_ID,
                    List.of(
                        getExpectedCityViews("New York", 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                    TOP_COUNTRIES_REPORT_ID,
                    List.of(
                        getExpectedCountryViews("US", "United States", 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID,
                    TOTAL_DOWNLOADS_REPORT_ID,
                    List.of()
                )
            )));
    }

    @Test
    public void usageReportsSearch_ItemVisited_FilesVisited() throws Exception {
        context.turnOffAuthorisationSystem();
        Bitstream bitstream1 =
            BitstreamBuilder.createBitstream(context, itemVisited, toInputStream("test", UTF_8)).withName("bitstream1")
                            .build();
        Bitstream bitstream2 =
            BitstreamBuilder.createBitstream(context, itemVisited, toInputStream("test", UTF_8)).withName("bitstream2")
                            .build();
        context.restoreAuthSystemState();

        // ** WHEN **
        // We visit an item
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("item");
        viewEventRest.setTargetId(itemVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                   .andExpect(status().isCreated());

        // And its two files, second one twice
        ViewEventRest viewEventRestBit1 = new ViewEventRest();
        viewEventRestBit1.setTargetType("bitstream");
        viewEventRestBit1.setTargetId(bitstream1.getID());
        ViewEventRest viewEventRestBit2 = new ViewEventRest();
        viewEventRestBit2.setTargetType("bitstream");
        viewEventRestBit2.setTargetId(bitstream2.getID());

        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRestBit1))
            .contentType(contentType))
                   .andExpect(status().isCreated());
        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRestBit2))
            .contentType(contentType))
                   .andExpect(status().isCreated());
        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRestBit2))
            .contentType(contentType))
                   .andExpect(status().isCreated());

        // And request the community usage reports
        getClient(adminToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/items/" + itemVisited.getID()))
            // ** THEN **
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.usagereports", not(empty())))
            .andExpect(jsonPath("$._embedded.usagereports", Matchers.containsInAnyOrder(
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                    TOTAL_VISITS_REPORT_ID,
                    List.of(
                        getExpectedDsoViews(itemVisited, 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    getListOfVisitsPerMonthsPoints(1)
                ),
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                    TOP_CITIES_REPORT_ID,
                    List.of(
                        getExpectedCityViews("New York", 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                    TOP_COUNTRIES_REPORT_ID,
                    List.of(
                        getExpectedCountryViews("US", "United States", 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    itemVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID,
                    TOTAL_DOWNLOADS_REPORT_ID,
                    List.of(
                        getExpectedDsoViews(bitstream1, 1),
                        getExpectedDsoViews(bitstream2, 2)
                    )
                )
            )));
    }

    @Test
    public void usageReportsSearch_Bitstream_Visited() throws Exception {
        // ** WHEN **
        // We visit a bitstream
        ViewEventRest viewEventRest = new ViewEventRest();
        viewEventRest.setTargetType("bitstream");
        viewEventRest.setTargetId(bitstreamVisited.getID());

        ObjectMapper mapper = new ObjectMapper();

        getClient().perform(post("/api/statistics/viewevents")
            .content(mapper.writeValueAsBytes(viewEventRest))
            .contentType(contentType))
                   .andExpect(status().isCreated());

        List<UsageReportPointRest> expectedTotalVisits = List.of(
            getExpectedDsoViews(bitstreamVisited, 1)
        );

        // And request the community usage reports
        getClient(adminToken)
            .perform(get("/api/statistics/usagereports/search/object?uri=http://localhost:8080/server/api/core" +
                         "/items/" + bitstreamVisited.getID()))
            // ** THEN **
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.usagereports", not(empty())))
            .andExpect(jsonPath("$._embedded.usagereports", Matchers.containsInAnyOrder(
                UsageReportMatcher.matchUsageReport(
                    bitstreamVisited.getID() + "_" + TOTAL_VISITS_REPORT_ID,
                    TOTAL_VISITS_REPORT_ID,
                    expectedTotalVisits
                ),
                UsageReportMatcher.matchUsageReport(
                    bitstreamVisited.getID() + "_" + TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    TOTAL_VISITS_PER_MONTH_REPORT_ID,
                    this.getListOfVisitsPerMonthsPoints(1)
                ),
                UsageReportMatcher.matchUsageReport(
                    bitstreamVisited.getID() + "_" + TOP_CITIES_REPORT_ID,
                    TOP_CITIES_REPORT_ID,
                    List.of(
                        getExpectedCityViews("New York", 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    bitstreamVisited.getID() + "_" + TOP_COUNTRIES_REPORT_ID,
                    TOP_COUNTRIES_REPORT_ID,
                    List.of(
                        getExpectedCountryViews("US", "United States", 1)
                    )
                ),
                UsageReportMatcher.matchUsageReport(
                    bitstreamVisited.getID() + "_" + TOTAL_DOWNLOADS_REPORT_ID,
                    TOTAL_DOWNLOADS_REPORT_ID,
                    expectedTotalVisits
                )
            )));
    }

    // Create expected points from usage-statistics.startDateInterval months back to now, with the given number of
    // views in the current month. Derives the window from config so the expectations track whatever
    // startDateInterval is configured (e.g. -6 for six months, -60 for five years of browsable history).
    private List<UsageReportPointRest> getListOfVisitsPerMonthsPoints(int viewsLastMonth) {
        List<UsageReportPointRest> expectedPoints = new ArrayList<>();
        int nrOfMonthsBack = Math.abs(configurationService.getIntProperty("usage-statistics.startDateInterval", -6));
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i <= nrOfMonthsBack; i++) {
            UsageReportPointDateRest expectedPoint = new UsageReportPointDateRest();
            if (i > 0) {
                expectedPoint.addValue("views", 0);
            } else {
                expectedPoint.addValue("views", viewsLastMonth);
            }
            String month = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, new Locale("en"));
            expectedPoint.setId(month + " " + cal.get(Calendar.YEAR));

            expectedPoints.add(expectedPoint);
            cal.add(Calendar.MONTH, -1);
        }
        return expectedPoints;
    }

    private UsageReportPointDsoTotalVisitsRest getExpectedDsoViews(DSpaceObject dso, int views) {
        UsageReportPointDsoTotalVisitsRest point = new UsageReportPointDsoTotalVisitsRest();

        point.addValue("views", views);
        point.setType(StringUtils.lowerCase(Constants.typeText[dso.getType()]));
        point.setId(dso.getID().toString());
        point.setLabel(dso.getName());

        return point;
    }

    private UsageReportPointCountryRest getExpectedCountryViews(String id, String label, int views) {
        UsageReportPointCountryRest point = new UsageReportPointCountryRest();

        point.addValue("views", views);
        point.setId(id);
        point.setLabel(label);

        return point;
    }

    private UsageReportPointCityRest getExpectedCityViews(String id, int views) {
        UsageReportPointCityRest point = new UsageReportPointCityRest();

        point.addValue("views", views);
        point.setId(id);

        return point;
    }
}

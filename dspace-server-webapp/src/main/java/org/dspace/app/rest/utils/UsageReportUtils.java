/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.utils;

import java.io.IOException;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.app.rest.model.UsageReportPointCityRest;
import org.dspace.app.rest.model.UsageReportPointCountryRest;
import org.dspace.app.rest.model.UsageReportPointDateRest;
import org.dspace.app.rest.model.UsageReportPointDsoTotalVisitsRest;
import org.dspace.app.rest.model.UsageReportRest;
import org.dspace.content.Bitstream;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.Site;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;
import org.dspace.statistics.Dataset;
import org.dspace.statistics.content.DatasetDSpaceObjectGenerator;
import org.dspace.statistics.content.DatasetTimeGenerator;
import org.dspace.statistics.content.DatasetTypeGenerator;
import org.dspace.statistics.content.StatisticsDataVisits;
import org.dspace.statistics.content.StatisticsListing;
import org.dspace.statistics.content.StatisticsTable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.stereotype.Component;

/**
 * This is the Service dealing with the {@link UsageReportRest} logic
 *
 * @author Maria Verdonck (Atmire) on 08/06/2020
 */
@Component
public class UsageReportUtils {

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private HandleService handleService;

    public static final String TOTAL_VISITS_REPORT_ID = "TotalVisits";
    public static final String TOTAL_VISITS_PER_MONTH_REPORT_ID = "TotalVisitsPerMonth";
    public static final String TOTAL_DOWNLOADS_REPORT_ID = "TotalDownloads";
    public static final String TOP_COUNTRIES_REPORT_ID = "TopCountries";
    public static final String TOP_CITIES_REPORT_ID = "TopCities";

    /**
     * Read a configured usage-report row limit, treating a value of 0 or lower as "no limit".
     * <p>
     * A non-positive value is translated to {@link Integer#MAX_VALUE} rather than -1 so that every row is
     * returned and the UI can paginate through all of them instead of only ever showing the first page.
     * We deliberately avoid -1: {@code SolrLogger} only skips applying a Solr facet limit when the value is
     * exactly -1, which falls back to Solr's default facet limit of 100 (not "unlimited"). A large facet
     * limit is safe because Solr only returns the facet values that actually exist, not empty buckets.
     *
     * @param property configuration property holding the limit (e.g. {@code usage-statistics.topCitiesLimit})
     * @return the configured positive limit, or {@link Integer#MAX_VALUE} when it is 0 or negative
     */
    private int getConfiguredLimit(String property) {
        int limit = configurationService.getIntProperty(property, -1);
        return limit <= 0 ? Integer.MAX_VALUE : limit;
    }

    /**
     * Get list of usage reports that are applicable to the DSO (of given UUID)
     *
     * @param context   DSpace context
     * @param dso       DSpaceObject we want all available usage reports of
     * @return List of usage reports, applicable to the given DSO
     */
    public List<UsageReportRest> getUsageReportsOfDSO(Context context, DSpaceObject dso)
        throws SQLException, ParseException, SolrServerException, IOException {
        List<UsageReportRest> usageReports = new ArrayList<>();
        if (dso instanceof Site) {
            UsageReportRest globalUsageStats = this.resolveGlobalUsageReport(context);
            globalUsageStats.setId(dso.getID().toString() + "_" + TOTAL_VISITS_REPORT_ID);
            usageReports.add(globalUsageStats);
        } else {
            usageReports.add(this.createUsageReport(context, dso, TOTAL_VISITS_REPORT_ID));
            usageReports.add(this.createUsageReport(context, dso, TOTAL_VISITS_PER_MONTH_REPORT_ID));
            usageReports.add(this.createUsageReport(context, dso, TOP_COUNTRIES_REPORT_ID));
            usageReports.add(this.createUsageReport(context, dso, TOP_CITIES_REPORT_ID));
        }
        if (dso instanceof Item || dso instanceof Bitstream) {
            usageReports.add(this.createUsageReport(context, dso, TOTAL_DOWNLOADS_REPORT_ID));
        }
        return usageReports;
    }

    /**
     * Creates the stat different stat usage report based on the report id.
     * If the report id or the object uuid is invalid, an exception is thrown.
     *
     * @param context  DSpace context
     * @param dso     DSpace object we want a stat usage report on
     * @param reportId Type of usage report requested
     * @return Rest object containing the stat usage report, see {@link UsageReportRest}
     */
    public UsageReportRest createUsageReport(Context context, DSpaceObject dso, String reportId)
        throws ParseException, SolrServerException, IOException {
        try {
            UsageReportRest usageReportRest;
            switch (reportId) {
                case TOTAL_VISITS_REPORT_ID:
                    usageReportRest = resolveTotalVisits(context, dso);
                    usageReportRest.setReportType(TOTAL_VISITS_REPORT_ID);
                    break;
                case TOTAL_VISITS_PER_MONTH_REPORT_ID:
                    usageReportRest = resolveTotalVisitsPerMonth(context, dso);
                    usageReportRest.setReportType(TOTAL_VISITS_PER_MONTH_REPORT_ID);
                    break;
                case TOTAL_DOWNLOADS_REPORT_ID:
                    usageReportRest = resolveTotalDownloads(context, dso);
                    usageReportRest.setReportType(TOTAL_DOWNLOADS_REPORT_ID);
                    break;
                case TOP_COUNTRIES_REPORT_ID:
                    usageReportRest = resolveTopCountries(context, dso);
                    usageReportRest.setReportType(TOP_COUNTRIES_REPORT_ID);
                    break;
                case TOP_CITIES_REPORT_ID:
                    usageReportRest = resolveTopCities(context, dso);
                    usageReportRest.setReportType(TOP_CITIES_REPORT_ID);
                    break;
                default:
                    throw new ResourceNotFoundException("The given report id can't be resolved: " + reportId + "; " +
                                                        "available reports: TotalVisits, TotalVisitsPerMonth, " +
                                                        "TotalDownloads, TopCountries, TopCities");
            }
            usageReportRest.setId(dso.getID() + "_" + reportId);
            return usageReportRest;
        } catch (SQLException e) {
            throw new SolrServerException("SQLException trying to receive statistics of: " + dso.getID());
        }
    }

    /**
     * Create stat usage report of the items most popular over entire site
     *
     * @param context DSpace context
     * @return Usage report with top most popular items
     */
    private UsageReportRest resolveGlobalUsageReport(Context context)
        throws SQLException, IOException, ParseException, SolrServerException {
        // A value of 0 or lower means "no limit": return statistics for every item so the UI can paginate
        // through all datasets instead of only ever showing the first page (see getConfiguredLimit).
        int topItemsLimit = getConfiguredLimit("usage-statistics.topItemsLimit");

        StatisticsListing statListing = new StatisticsListing(
            new StatisticsDataVisits());

        // Adding a new generator for our top n items without a name length delimiter
        DatasetDSpaceObjectGenerator dsoAxis = new DatasetDSpaceObjectGenerator();
        dsoAxis.addDsoChild(Constants.ITEM, topItemsLimit, false, -1);
        statListing.addDatasetGenerator(dsoAxis);

        Dataset dataset = statListing.getDataset(context, 1);

        UsageReportRest usageReportRest = new UsageReportRest();
        for (int i = 0; i < dataset.getColLabels().size(); i++) {
            UsageReportPointDsoTotalVisitsRest totalVisitPoint = new UsageReportPointDsoTotalVisitsRest();
            totalVisitPoint.setType("item");
            String urlOfItem = dataset.getColLabelsAttrs().get(i).get("url");
            if (urlOfItem != null) {
                String handle = StringUtils.substringAfterLast(urlOfItem, "handle/");
                if (handle != null) {
                    DSpaceObject dso = handleService.resolveToObject(context, handle);
                    totalVisitPoint.setId(dso != null ? dso.getID().toString() : urlOfItem);
                    totalVisitPoint.setLabel(dso != null ? dso.getName() : urlOfItem);
                    totalVisitPoint.addValue("views", Integer.valueOf(dataset.getMatrix()[0][i]));
                    usageReportRest.addPoint(totalVisitPoint);
                }
            }
        }
        usageReportRest.setReportType(TOTAL_VISITS_REPORT_ID);
        return usageReportRest;
    }

    /**
     * Create a stat usage report for the amount of TotalVisit on a DSO, containing one point with the amount of
     * views on the DSO in. If there are no views on the DSO this point contains views=0.
     *
     * @param context DSpace context
     * @param dso     DSO we want usage report with TotalVisits on the DSO
     * @return Rest object containing the TotalVisits usage report of the given DSO
     */
    private UsageReportRest resolveTotalVisits(Context context, DSpaceObject dso)
        throws SQLException, IOException, ParseException, SolrServerException {
        // The TotalVisits report facets on the DSO itself and reads a single aggregate point, so the child
        // limit is inert here; keep the historical value of 10 rather than coupling it to a report limit.
        Dataset dataset = this.getDSOStatsDataset(context, dso, 1, dso.getType(), 10);

        UsageReportRest usageReportRest = new UsageReportRest();
        UsageReportPointDsoTotalVisitsRest totalVisitPoint = new UsageReportPointDsoTotalVisitsRest();
        totalVisitPoint.setType(StringUtils.substringAfterLast(dso.getClass().getName().toLowerCase(), "."));
        totalVisitPoint.setId(dso.getID().toString());
        if (!dataset.getColLabels().isEmpty()) {
            totalVisitPoint.setLabel(dso.getName());
            totalVisitPoint.addValue("views", Integer.valueOf(dataset.getMatrix()[0][0]));
        } else {
            totalVisitPoint.setLabel(dso.getName());
            totalVisitPoint.addValue("views", 0);
        }

        usageReportRest.addPoint(totalVisitPoint);
        return usageReportRest;
    }

    /**
     * Create a stat usage report for the amount of TotalVisitPerMonth on a DSO, containing one point for each month
     * with the views on that DSO in that month with the range -6 months to now. If there are no views on the DSO
     * in a month, the point on that month contains views=0.
     *
     * @param context DSpace context
     * @param dso     DSO we want usage report with TotalVisitsPerMonth to the DSO
     * @return Rest object containing the TotalVisits usage report on the given DSO
     */
    private UsageReportRest resolveTotalVisitsPerMonth(Context context, DSpaceObject dso)
        throws SQLException, IOException, ParseException, SolrServerException {
        String startDateInterval =
            configurationService.getProperty("usage-statistics.startDateInterval", "-6");
        String endDateInterval =
            configurationService.getProperty("usage-statistics.endDateInterval", "+1");

        StatisticsTable statisticsTable = new StatisticsTable(new StatisticsDataVisits(dso));
        DatasetTimeGenerator timeAxis = new DatasetTimeGenerator();
        timeAxis.setDateInterval("month", startDateInterval, endDateInterval);
        statisticsTable.addDatasetGenerator(timeAxis);
        DatasetDSpaceObjectGenerator dsoAxis = new DatasetDSpaceObjectGenerator();
        // Use max = -1 so StatisticsDataVisits takes its direct date-facet path: a single Solr date-range facet
        // over the DSO's visits query, which zero-fills every month in the configured window. The previous
        // max = 10 forced the facet-DSOs-then-dates path, which returns an empty report (no month points at
        // all) whenever the statistics core holds no matching documents yet — contradicting this report's
        // contract that months without views are returned with views=0.
        dsoAxis.addDsoChild(dso.getType(), -1, false, -1);
        statisticsTable.addDatasetGenerator(dsoAxis);
        Dataset dataset = statisticsTable.getDataset(context, 0);

        UsageReportRest usageReportRest = new UsageReportRest();
        // Solr returns the months oldest-first; emit them newest-first so the most recent month is on the first
        // page and users page backwards through history (see issue #807).
        for (int i = dataset.getColLabels().size() - 1; i >= 0; i--) {
            UsageReportPointDateRest monthPoint = new UsageReportPointDateRest();
            monthPoint.setId(dataset.getColLabels().get(i));
            monthPoint.addValue("views", Integer.valueOf(dataset.getMatrix()[0][i]));
            usageReportRest.addPoint(monthPoint);
        }
        return usageReportRest;
    }

    /**
     * Create a stat usage report for the amount of TotalDownloads on the files of an Item or of a Bitstream,
     * containing a point for each bitstream of the item that has been visited at least once or one point for the
     * bitstream containing the amount of times that bitstream has been visited (even if 0)
     * If the item has no bitstreams, or no bitstreams that have ever been downloaded/visited, then it contains an
     * empty list of points=[]
     * If the given UUID is for DSO that is neither a Bitstream nor an Item, an exception is thrown.
     *
     * @param context DSpace context
     * @param dso     Item/Bitstream we want usage report on with TotalDownloads of the Item's bitstreams or of the
     *                bitstream itself
     * @return Rest object containing the TotalDownloads usage report on the given Item/Bitstream
     */
    private UsageReportRest resolveTotalDownloads(Context context, DSpaceObject dso)
        throws SQLException, SolrServerException, ParseException, IOException {
        if (dso instanceof org.dspace.content.Bitstream) {
            return this.resolveTotalVisits(context, dso);
        }

        if (dso instanceof org.dspace.content.Item) {
            // Limit the number of bitstream rows by config; 0 or negative returns all bitstreams so the UI
            // can paginate through every file instead of only the first page (see getConfiguredLimit).
            int topDownloadsLimit = getConfiguredLimit("usage-statistics.topDownloadsLimit");
            Dataset dataset = this.getDSOStatsDataset(context, dso, 1, Constants.BITSTREAM, topDownloadsLimit);

            UsageReportRest usageReportRest = new UsageReportRest();
            for (int i = 0; i < dataset.getColLabels().size(); i++) {
                UsageReportPointDsoTotalVisitsRest totalDownloadsPoint = new UsageReportPointDsoTotalVisitsRest();
                totalDownloadsPoint.setType("bitstream");

                totalDownloadsPoint.setId(dataset.getColLabelsAttrs().get(i).get("id"));
                totalDownloadsPoint.setLabel(dataset.getColLabels().get(i));

                totalDownloadsPoint.addValue("views", Integer.valueOf(dataset.getMatrix()[0][i]));
                usageReportRest.addPoint(totalDownloadsPoint);
            }
            return usageReportRest;
        }
        throw new IllegalArgumentException("TotalDownloads report only available for items and bitstreams");
    }

    /**
     * Create a stat usage report for the TopCountries that have visited the given DSO. If there have been no visits, or
     * no visits with a valid Geolite determined country (based on IP), this report contains an empty list of points=[].
     * The list of points is limited by {@code usage-statistics.topCountriesLimit} (0 or negative = all countries, the
     * UI paginates through them), and each point contains the country name, its iso code and the amount of views on
     * the given DSO from that country.
     *
     * @param context DSpace context
     * @param dso     DSO we want usage report of the TopCountries on the given DSO
     * @return Rest object containing the TopCountries usage report on the given DSO
     */
    private UsageReportRest resolveTopCountries(Context context, DSpaceObject dso)
        throws SQLException, IOException, ParseException, SolrServerException {
        int topCountriesLimit = getConfiguredLimit("usage-statistics.topCountriesLimit");

        Dataset dataset = this.getTypeStatsDataset(context, dso, "countryCode", topCountriesLimit, 1);

        UsageReportRest usageReportRest = new UsageReportRest();
        for (int i = 0; i < dataset.getColLabels().size(); i++) {
            UsageReportPointCountryRest countryPoint = new UsageReportPointCountryRest();
            countryPoint.setLabel(dataset.getColLabels().get(i));
            countryPoint.addValue("views", Integer.valueOf(dataset.getMatrix()[0][i]));
            usageReportRest.addPoint(countryPoint);
        }
        return usageReportRest;
    }

    /**
     * Create a stat usage report for the TopCities that have visited the given DSO. If there have been no visits, or
     * no visits with a valid Geolite determined city (based on IP), this report contains an empty list of points=[].
     * The list of points is limited by {@code usage-statistics.topCitiesLimit} (0 or negative = all cities, the UI
     * paginates through them), and each point contains the city name and the amount of views on the given DSO from
     * that city.
     *
     * @param context DSpace context
     * @param dso     DSO we want usage report of the TopCities on the given DSO
     * @return Rest object containing the TopCities usage report on the given DSO
     */
    private UsageReportRest resolveTopCities(Context context, DSpaceObject dso)
        throws SQLException, IOException, ParseException, SolrServerException {
        int topCitiesLimit = getConfiguredLimit("usage-statistics.topCitiesLimit");

        Dataset dataset = this.getTypeStatsDataset(context, dso, "city", topCitiesLimit, 1);

        UsageReportRest usageReportRest = new UsageReportRest();
        for (int i = 0; i < dataset.getColLabels().size(); i++) {
            UsageReportPointCityRest cityPoint = new UsageReportPointCityRest();
            cityPoint.setId(dataset.getColLabels().get(i));
            cityPoint.addValue("views", Integer.valueOf(dataset.getMatrix()[0][i]));
            usageReportRest.addPoint(cityPoint);
        }
        return usageReportRest;
    }

    /**
     * Retrieves the stats dataset of a given DSO, of given type, with a given facetMinCount limit (usually either 0
     * or 1, 0 if we want a data point even though the facet data point has 0 matching results).
     *
     * @param context       DSpace context
     * @param dso           DSO we want the stats dataset of
     * @param facetMinCount Minimum amount of results on a facet data point for it to be added to dataset
     * @param dsoType       Type of DSO we want the stats dataset of
     * @param max           Maximum amount of child rows to return on the DSO axis (e.g. bitstreams of an item);
     *                      use {@link Integer#MAX_VALUE} for "no limit" (see getConfiguredLimit)
     * @return Stats dataset with the given filters.
     */
    private Dataset getDSOStatsDataset(Context context, DSpaceObject dso, int facetMinCount, int dsoType, int max)
        throws SQLException, IOException, ParseException, SolrServerException {
        StatisticsListing statsList = new StatisticsListing(new StatisticsDataVisits(dso));
        DatasetDSpaceObjectGenerator dsoAxis = new DatasetDSpaceObjectGenerator();
        dsoAxis.addDsoChild(dsoType, max, false, -1);
        statsList.addDatasetGenerator(dsoAxis);
        return statsList.getDataset(context, facetMinCount);
    }

    /**
     * Retrieves the stats dataset of a given dso, with a given axisType (example countryCode, city), which
     * corresponds to a solr field, and a given facetMinCount limit (usually either 0 or 1, 0 if we want a data point
     * even though the facet data point has 0 matching results).
     *
     * @param context        DSpace context
     * @param dso            DSO we want the stats dataset of
     * @param typeAxisString String of the type we want on the axis of the dataset (corresponds to solr field),
     *                       examples: countryCode, city
     * @param typeAxisMax    Maximum amount of results to return in the dataset
     * @param facetMinCount  Minimum amount of results on a facet data point for it to be added to dataset
     * @return Stats dataset with the given type on the axis, of the given DSO and with given facetMinCount
     */
    private Dataset getTypeStatsDataset(Context context, DSpaceObject dso, String typeAxisString, int typeAxisMax,
                                        int facetMinCount)
        throws SQLException, IOException, ParseException, SolrServerException {
        StatisticsListing statListing = new StatisticsListing(new StatisticsDataVisits(dso));
        DatasetTypeGenerator typeAxis = new DatasetTypeGenerator();
        typeAxis.setType(typeAxisString);
        typeAxis.setMax(typeAxisMax);
        statListing.addDatasetGenerator(typeAxis);
        return statListing.getDataset(context, facetMinCount);
    }
}

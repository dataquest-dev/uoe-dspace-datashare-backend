/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.crosswalk;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotNull;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.core.factory.CoreServiceFactory;
import org.jdom2.Element;
import org.jdom2.output.XMLOutputter;
import org.junit.Test;

/**
 * End-to-end integration test for the Edinburgh DataShare customisations of the
 * DataCite dissemination crosswalk ({@code config/crosswalks/DIM2DataCite.xsl}).
 *
 * <p>This exercises the <em>real</em> production path that {@code DataCiteConnector}
 * / {@code DataCiteXMLCreator} use when registering a DOI: it builds a real DSpace
 * {@link Item} (backed by the in-memory database), looks up the named "DataCite"
 * {@link DisseminationCrosswalk} plugin exactly as production does, and calls
 * {@code disseminateElement}. That runs DSpace's own DIM assembly
 * ({@code XSLTDisseminationCrosswalk.createDIM} over {@code itemService.getMetadata})
 * and the crosswalk transform with the same Saxon engine used in production — so it
 * proves the fix works against the metadata DSpace actually produces, not just a
 * hand-written fixture.
 *
 * <p>The item mirrors the live broken item handle 10283/9230 (issue #786): authors
 * in {@code dc.creator}, depositor in a bare {@code dc.contributor}, a funder in
 * {@code dc.contributor.other}, and a lower-case {@code dc.type}.
 */
public class DataCiteDisseminationCrosswalkIT extends AbstractIntegrationTestWithDatabase {

    @Test
    public void datashareItemProducesRealCreatorsDatasetTypeAndContactPerson() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection").build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Kinetic and Thermodynamic Crystallization Events")
                .withMetadata("dc", "creator", null, "Abdullah, Rana")
                .withMetadata("dc", "creator", null, "Gromov, Andrei")
                .withMetadata("dc", "creator", null, "Morrison, Carole")
                .withMetadata("dc", "contributor", null, "Nudelman, Fabio")
                .withMetadata("dc", "contributor", "other",
                        "EPSRC - Engineering and Physical Sciences Research Council")
                .withMetadata("dc", "publisher", null, "University of Edinburgh. School of Chemistry")
                .withMetadata("dc", "identifier", "uri", "https://doi.org/10.5072/dspace-testit")
                .withMetadata("dc", "identifier", "citation",
                        "Abdullah, Rana. (2026). t, [dataset]. UoE. https://doi.org/10.5072/dspace-testit.")
                .withMetadata("dc", "relation", "isreferencedby", "https://doi.org/10.1021/acs.cgd.6c00474")
                .withType("dataset")
                .withIssueDate("2026-06-17")
                .build();
        context.restoreAuthSystemState();

        DisseminationCrosswalk crosswalk = (DisseminationCrosswalk) CoreServiceFactory.getInstance()
                .getPluginService().getNamedPlugin(DisseminationCrosswalk.class, "DataCite");
        assertNotNull("DataCite dissemination crosswalk must be registered", crosswalk);

        Element resource = crosswalk.disseminateElement(context, item);
        String xml = new XMLOutputter().outputString(resource);

        // C5: authors restored from dc.creator (was "(:unkn) unknown")
        assertThat(xml, containsString("<creatorName>Abdullah, Rana</creatorName>"));
        assertThat(xml, containsString("<creatorName>Gromov, Andrei</creatorName>"));
        assertThat(xml, containsString("<creatorName>Morrison, Carole</creatorName>"));
        assertThat(xml, not(containsString("(:unkn) unknown")));

        // C6: lower-case dc.type -> resourceTypeGeneral="Dataset" (was "Other")
        assertThat(xml, containsString("resourceTypeGeneral=\"Dataset\""));

        // C7: depositor -> ContactPerson; no bogus "My University" DataManager/HostingInstitution
        assertThat(xml, containsString("contributorType=\"ContactPerson\""));
        assertThat(xml, containsString("<contributorName>Nudelman, Fabio</contributorName>"));
        assertThat(xml, not(containsString("DataManager")));
        assertThat(xml, not(containsString("HostingInstitution")));
        assertThat(xml, not(containsString("My University")));

        // C8 reverted: dc.contributor.other is dropped, as v5 did
        assertThat(xml, not(containsString("fundingReference")));
        assertThat(xml, not(containsString("EPSRC")));

        // C9: dc.identifier.citation kept as alternateIdentifier (only the primary DOI excluded)
        assertThat(xml, containsString("alternateIdentifierType=\"citation\""));
        assertThat(xml, containsString("<identifier identifierType=\"DOI\">10.5072/dspace-testit</identifier>"));
        // C9: dc.relation.* URL -> relatedIdentifier
        assertThat(xml, containsString("relationType=\"IsReferencedBy\""));
        assertThat(xml, containsString("https://doi.org/10.1021/acs.cgd.6c00474"));
    }
}

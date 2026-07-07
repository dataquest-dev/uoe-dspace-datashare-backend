/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.tests.stylesheets;

import static org.dspace.xoai.tests.support.XmlMatcherBuilder.xml;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.dspace.xoai.tests.support.XmlMatcherBuilder;
import org.junit.Test;

/**
 * Tests for the Edinburgh DataShare customisations of the DataCite dissemination
 * crosswalk ({@code dspace/config/crosswalks/DIM2DataCite.xsl}) that
 * {@code org.dspace.identifier.doi.DataCiteConnector} uses to build the metadata
 * registered for a DOI.
 *
 * <p>DataShare deposits author names in {@code dc.creator} (the DSpace 5.x -&gt; 8
 * migration moved them out of {@code dc.contributor.author}), and stores
 * {@code dc.type} lower-case (e.g. {@code "dataset"}). The vanilla crosswalk only
 * read {@code dc.contributor.author} and compared {@code dc.type} case-sensitively,
 * so migrated items registered DOIs with {@code "(:unkn) unknown"} creators and
 * {@code resourceTypeGeneral="Other"} (issue #786).
 *
 * <p>These tests pin the patched behaviour, which follows the "Mapping between
 * Datashare and DataCite metadata fields" wiki:
 * <ul>
 *   <li>creators are built from {@code dc.creator} as well as
 *       {@code dc.contributor.author}, with a {@code dc.publisher} fallback before
 *       the {@code (:unkn) unknown} placeholder;</li>
 *   <li>a present-but-blank creator value does not emit an empty (schema-invalid)
 *       {@code creatorName} nor suppress the {@code (:unkn) unknown} fallback;</li>
 *   <li>{@code dc.type} is mapped to {@code resourceTypeGeneral} per the spec and
 *       matched case-insensitively so {@code "dataset"} keeps {@code "Dataset"};</li>
 *   <li>{@code dc.contributor} (the depositor) becomes a {@code ContactPerson} and the
 *       vanilla {@code DataManager}/{@code HostingInstitution} ("My University")
 *       contributors are gone;</li>
 *   <li>{@code dc.contributor.other} (funders) becomes {@code fundingReferences}.</li>
 * </ul>
 *
 * <p>Mirrors the {@link AbstractXSLTest}/{@link OpenaireXslTest} harness (offline
 * Saxon transform of a DIM fixture, asserted via XPath). Run with:
 * {@code mvn -pl dspace-oai test -DskipUnitTests=false -Dtest=DataCiteXslTest}
 */
public class DataCiteXslTest {

    // The DataCite crosswalk is XSLT 2.0 (uses current-date(), lower-case()), so it
    // requires Saxon just like the OAI stylesheets exercised by AbstractXSLTest.
    private static final TransformerFactory factory =
        TransformerFactory.newInstance("net.sf.saxon.TransformerFactoryImpl", null);

    private static final File CROSSWALK =
        new File("../dspace/config/crosswalks/DIM2DataCite.xsl");

    private String transform(String fixture) throws Exception {
        InputStream input = getClass().getClassLoader().getResourceAsStream(fixture);
        if (input == null) {
            throw new IllegalArgumentException("Test fixture not found on classpath: " + fixture);
        }
        try (input) {
            Transformer transformer = factory.newTransformer(new StreamSource(CROSSWALK));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new StreamSource(input), new StreamResult(out));
            // Decode as UTF-8 explicitly: the crosswalk emits UTF-8 and the default platform
            // charset would make the XML/XPath assertions environment-dependent.
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** dc.creator alone must populate the creators (vanilla read only dc.contributor.author). */
    @Test
    public void buildsCreatorsFromDcCreator() throws Exception {
        String result = transform("dim-datacite-creator.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:creators/d:creator)", equalTo("3"))
                .withXPath("//d:creators/d:creator[1]/d:creatorName", equalTo("Abdullah, Rana"))
                .withXPath("//d:creators/d:creator[2]/d:creatorName", equalTo("Gromov, Andrei"))
                .withXPath("//d:creators/d:creator[3]/d:creatorName", equalTo("Morrison, Carole"))
                .withXPath("count(//d:creatorName[normalize-space(.)='(:unkn) unknown'])", equalTo("0"))));
    }

    /** A lower-case dc.type ("dataset") must map to resourceTypeGeneral="Dataset", not "Other". */
    @Test
    public void mapsLowerCaseDatasetTypeToDataset() throws Exception {
        String result = transform("dim-datacite-creator.xml");

        assertThat(result, is(datacite()
                .withXPath("//d:resourceType/@resourceTypeGeneral", equalTo("Dataset"))));
    }

    /** Regression: dc.contributor.author still populates the creators. */
    @Test
    public void buildsCreatorsFromDcContributorAuthor() throws Exception {
        String result = transform("dim-datacite-author.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:creators/d:creator)", equalTo("1"))
                .withXPath("//d:creators/d:creator/d:creatorName", equalTo("Smith, John"))));
    }

    /** Regression: an already-capitalised dc.type ("Dataset") still maps to "Dataset". */
    @Test
    public void mapsCapitalisedDatasetTypeToDataset() throws Exception {
        String result = transform("dim-datacite-author.xml");

        assertThat(result, is(datacite()
                .withXPath("//d:resourceType/@resourceTypeGeneral", equalTo("Dataset"))));
    }

    /** With neither dc.contributor.author nor dc.creator, the (:unkn) unknown fallback is kept. */
    @Test
    public void keepsUnknownFallbackWhenNoAuthors() throws Exception {
        String result = transform("dim-datacite-no-creators.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:creators/d:creator)", equalTo("1"))
                .withXPath("//d:creators/d:creator/d:creatorName", equalTo("(:unkn) unknown"))));
    }

    /** A lower-case "software" type is matched case-insensitively. */
    @Test
    public void mapsLowerCaseSoftwareTypeToSoftware() throws Exception {
        String result = transform("dim-datacite-no-creators.xml");

        assertThat(result, is(datacite()
                .withXPath("//d:resourceType/@resourceTypeGeneral", equalTo("Software"))));
    }

    /**
     * A present-but-blank dc.creator must not emit an empty (schema-invalid) creatorName;
     * the item falls back to the (:unkn) unknown placeholder instead.
     */
    @Test
    public void blankCreatorFallsBackToUnknown() throws Exception {
        String result = transform("dim-datacite-blank-creator.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:creators/d:creator)", equalTo("1"))
                .withXPath("//d:creators/d:creator/d:creatorName", equalTo("(:unkn) unknown"))));
    }

    /** When both fields are present, both are emitted with dc.contributor.author first. */
    @Test
    public void buildsCreatorsFromBothFields() throws Exception {
        String result = transform("dim-datacite-both.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:creators/d:creator)", equalTo("2"))
                .withXPath("//d:creators/d:creator[1]/d:creatorName", equalTo("Author, Primary"))
                .withXPath("//d:creators/d:creator[2]/d:creatorName", equalTo("Creator, Secondary"))));
    }

    /** With no creator/author but a dc.publisher, the publisher is used as the creator. */
    @Test
    public void usesPublisherAsCreatorWhenNoCreator() throws Exception {
        String result = transform("dim-datacite-publisher-fallback.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:creators/d:creator)", equalTo("1"))
                .withXPath("//d:creators/d:creator/d:creatorName", equalTo("University of Edinburgh"))));
    }

    /** Spec mapping: dc.type "article" maps to resourceTypeGeneral="Text". */
    @Test
    public void mapsArticleTypeToText() throws Exception {
        String result = transform("dim-datacite-publisher-fallback.xml");

        assertThat(result, is(datacite()
                .withXPath("//d:resourceType/@resourceTypeGeneral", equalTo("Text"))));
    }

    /** The depositor (unqualified dc.contributor) maps to a ContactPerson contributor. */
    @Test
    public void mapsDepositorToContactPerson() throws Exception {
        String result = transform("dim-datacite-creator.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:contributors/d:contributor)", equalTo("1"))
                .withXPath("//d:contributors/d:contributor/@contributorType", equalTo("ContactPerson"))
                .withXPath("//d:contributors/d:contributor/d:contributorName", equalTo("Nudelman, Fabio"))));
    }

    /**
     * The vanilla DataManager/HostingInstitution contributors (which rendered as the
     * unconfigured "My University" placeholder) must no longer be emitted.
     */
    @Test
    public void doesNotEmitPlaceholderInstitutionContributors() throws Exception {
        String result = transform("dim-datacite-creator.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:contributor[@contributorType='DataManager'])", equalTo("0"))
                .withXPath("count(//d:contributor[@contributorType='HostingInstitution'])", equalTo("0"))
                .withXPath("count(//d:contributorName[contains(., 'My University')])", equalTo("0"))));
    }

    /** dc.contributor.other (funder) maps to a fundingReference, not a contributor. */
    @Test
    public void mapsFunderToFundingReference() throws Exception {
        String result = transform("dim-datacite-creator.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:fundingReferences/d:fundingReference)", equalTo("1"))
                .withXPath("//d:fundingReferences/d:fundingReference/d:funderName",
                        equalTo("EPSRC - Engineering and Physical Sciences Research Council"))
                .withXPath("count(//d:contributorName[contains(., 'EPSRC')])", equalTo("0"))));
    }

    /** No <contributors> element is emitted when there is no depositor contributor. */
    @Test
    public void omitsContributorsWhenNoDepositor() throws Exception {
        String result = transform("dim-datacite-both.xml");

        assertThat(result, is(datacite()
                .withXPath("count(//d:contributors)", equalTo("0"))));
    }

    private XmlMatcherBuilder datacite() {
        return xml().withNamespace("d", "http://datacite.org/schema/kernel-4");
    }
}

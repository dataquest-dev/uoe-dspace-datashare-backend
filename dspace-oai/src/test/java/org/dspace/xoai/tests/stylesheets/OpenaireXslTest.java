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

import org.dspace.xoai.tests.support.XmlMatcherBuilder;
import org.junit.Test;

/**
 * Tests for the Edinburgh DataShare customisations of the OpenAIRE v4 OAI
 * crosswalk ({@code oai_openaire.xsl}). DataShare deposits authors in
 * {@code dc.creator} (not {@code dc.contributor.author}) and rarely sets
 * {@code dc.date.issued}, so the vanilla crosswalk emitted empty — but
 * OpenAIRE-mandatory — {@code datacite:creators} and {@code datacite:dates}.
 *
 * <p>These tests pin the patched behaviour:
 * <ul>
 *   <li>creators are built from {@code dc.contributor.author} <em>and</em> {@code dc.creator};</li>
 *   <li>a mandatory Issued date is derived from {@code dc.date.available} then
 *       {@code dc.date.accessioned} when {@code dc.date.issued} is absent;</li>
 *   <li>dates are trimmed to {@code YYYY-MM-DD};</li>
 *   <li>two former defects no longer emit empty (schema-invalid) mandatory
 *       elements: a {@code dc.creator} virtual entity, and a present-but-blank
 *       {@code dc.date.issued}.</li>
 * </ul>
 */
public class OpenaireXslTest extends AbstractXSLTest {

    /** dc.creator alone must populate datacite:creators (vanilla read only dc.contributor.author). */
    @Test
    public void buildsCreatorsFromDcCreator() throws Exception {
        String result = apply("oai_openaire.xsl").to(resource("xoai-openaire-creator.xml"));

        assertThat(result, is(openaire()
                .withXPath("//datacite:creators/datacite:creator/datacite:creatorName", equalTo("Doe, Jane"))));
    }

    /** Regression: dc.contributor.author still populates datacite:creators. */
    @Test
    public void buildsCreatorsFromDcContributorAuthor() throws Exception {
        String result = apply("oai_openaire.xsl").to(resource("xoai-openaire-author.xml"));

        assertThat(result, is(openaire()
                .withXPath("//datacite:creators/datacite:creator/datacite:creatorName", equalTo("Smith, John"))));
    }

    /** When dc.date.issued is absent, a mandatory Issued date is derived from dc.date.available. */
    @Test
    public void derivesIssuedDateFromAvailableWhenIssuedMissing() throws Exception {
        String result = apply("oai_openaire.xsl").to(resource("xoai-openaire-creator.xml"));

        assertThat(result, is(openaire()
                .withXPath("//datacite:date[@dateType='Issued']", equalTo("2021-05-01"))
                .withXPath("//datacite:date[@dateType='Available']", equalTo("2021-05-01"))));
    }

    /** Full ISO timestamps are trimmed to YYYY-MM-DD. */
    @Test
    public void trimsIssuedTimestampToDate() throws Exception {
        String result = apply("oai_openaire.xsl").to(resource("xoai-openaire-author.xml"));

        assertThat(result, is(openaire()
                .withXPath("//datacite:date[@dateType='Issued']", equalTo("2022-11-30"))
                .withXPath("//datacite:date[@dateType='Accepted']", equalTo("2022-11-30"))));
    }

    /**
     * BUG 2: a present-but-blank dc.date.issued must not suppress the fallback
     * nor emit empty mandatory dates. Issued is derived from dc.date.available
     * and no (empty) Accepted date is produced.
     */
    @Test
    public void blankIssuedDoesNotProduceEmptyDates() throws Exception {
        String result = apply("oai_openaire.xsl").to(resource("xoai-openaire-empty-issued.xml"));

        assertThat(result, is(openaire()
                .withXPath("//datacite:date[@dateType='Issued']", equalTo("2020-03-15"))
                .withXPath("count(//datacite:date[@dateType='Issued'])", equalTo("1"))
                .withXPath("count(//datacite:date[@dateType='Accepted'])", equalTo("0"))));
    }

    /**
     * BUG 1: an author held in dc.creator as a virtual:: entity must yield a
     * non-empty creatorName (entity_creator previously matched only
     * dc.contributor.author.* and emitted an empty name for dc.creator entities).
     */
    @Test
    public void resolvesNameForDcCreatorVirtualEntity() throws Exception {
        String result = apply("oai_openaire.xsl").to(resource("xoai-openaire-creator-entity.xml"));

        assertThat(result, is(openaire()
                .withXPath("//datacite:creators/datacite:creator/datacite:creatorName", equalTo("Doe, John"))
                .withXPath("//datacite:creators/datacite:creator/datacite:familyName", equalTo("Doe"))
                .withXPath("//datacite:creators/datacite:creator/datacite:givenName", equalTo("John"))));
    }

    private XmlMatcherBuilder openaire() {
        return xml()
                .withNamespace("oaire", "http://namespace.openaire.eu/schema/oaire/")
                .withNamespace("datacite", "http://datacite.org/schema/kernel-4")
                .withNamespace("dc", "http://purl.org/dc/elements/1.1/");
    }
}

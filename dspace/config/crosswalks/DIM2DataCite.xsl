<?xml version="1.0" encoding="UTF-8"?>
<!-- // DATASHARE - start
     File modified for Edinburgh Datashare. Re-applied customizations on top of
     vanilla DSpace 8.3 DIM2DataCite.xsl (issue #049).
     Customizations:
       C1 — this header banner
       C2 — publicationYear: fallback to dc.date.accessioned, then current year
            (instead of "0000") to avoid registering DOIs with bogus year.
       C3 — Rights: handle dc.rights.uri qualifier as <rights rightsURI="…"/>.
       C4 — Rights: detect "Creative Commons Attribution 4.0" and add SPDX
            rightsIdentifier=CC-BY-4.0, schemeURI, etc.
       C5 — Creators: also build <creator>s from dc.creator (not only
            dc.contributor.author). DataShare holds author names in dc.creator, so
            the vanilla DSpace-8 crosswalk registered DOIs with "(:unkn) unknown"
            creators (issue #786).
       C6 — ResourceType: match dc.type case-insensitively (so lower-case "dataset"
            keeps resourceTypeGeneral="Dataset" instead of "Other", issue #786) and
            add the DataShare-specific values "moving image", "interactive resource"
            and "sound" that the vanilla list omits.
       C7 — Contributors: map dc.contributor (depositor) to ContactPerson and
            remove the vanilla DataManager/HostingInstitution contributors, which
            rendered as an unconfigured "My University" contributor on every DOI.
            dc.contributor.other is dropped, matching v5 (see DataCite (19)).
     These restore the pre-migration DataShare output verified against the live
     DataCite records of DOIs registered before the DSpace-8 migration.
     Search for "DATASHARE" markers below to locate each customization.
  -->

<!--
    Document   : DIM2DataCite.xsl
    Created on : January 23, 2013, 1:26 PM
    Updated on : Jaunary 30, 2024, 3:00 PM
    Author     : pbecker, ffuerste, ypaulsen
    Description: Converts metadata from DSpace Intermediate Format (DIM) into
                 metadata following the DataCite Metadata Schema 4.5
-->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:dspace="http://www.dspace.org/xmlns/dspace/dim"
                xmlns="http://datacite.org/schema/kernel-4"
                version="2.0">
    
    <!-- CONFIGURATION -->
    <!-- The parameters prefix, publisher, datamanager and hostinginstitution
         moved to DSpace's configuration. They will be substituted automatically.
         Please take a look into the DSpace documentation for details on how to
         change those. -->
    <!-- This file handles the transformation of metadata into the DataCite
         Schema. You should customize it to match your local metadata schema
         and submission forms. Please note that this must produce valid XML
         according to the DataCite Schema. Otherwise you will not be able
         to register DOIs anymore. Please follow and reuse the examples
         included in this file. For more information on the DataCite
         Schema, see https://schema.datacite.org. -->
    <!-- Note regarding language codes: xml:lang regional language codes require a hyphen, whereas many
         repositories use underscores when storing these language codes (e.g. en_GB, de_CH).
         This template translates all underscores to hyphens when selecting value of @lang in an attribute
         so the output will be e.g. xml:lang="en-GB", xml:lang="de-CH". -->
    
    <!-- We need the prefix to determine DOIs that were minted by ourself. -->
    <xsl:param name="prefix">10.5072/dspace-</xsl:param>
    <!-- The content of the following parameter will be used as element publisher. -->
    <xsl:param name="publisher">My University</xsl:param>
    <!-- // DATASHARE (C7): datamanager and hostinginstitution are still declared because
         DataCiteXMLCreator injects them from configuration, but they are no longer emitted
         as contributors (see DataCite (7) below). -->
    <xsl:param name="datamanager"><xsl:value-of select="$publisher" /></xsl:param>
    <xsl:param name="hostinginstitution"><xsl:value-of select="$publisher" /></xsl:param>
    <!-- Please take a look into the DataCite schema documentation if you want to know how to use these elements.
         http://schema.datacite.org -->
    <!-- Metadata-field to retrieve DOI from items -->
    <xsl:param name="mdSchema">dc</xsl:param>
    <xsl:param name="mdElement">identifier</xsl:param>
    <xsl:param name="mdQualifier">uri</xsl:param>

    <xsl:output method="xml" indent="yes" encoding="utf-8" />

    <!-- Don't copy everything by default! -->
    <xsl:template match="@* | text()" />

    <xsl:template match="/dspace:dim[@dspaceType='ITEM']">
        <!--
            org.dspace.identifier.doi.DataCiteConnector uses this XSLT to
            transform metadata for the DataCite metadata store. This crosswalk
            should only be used, when it is ensured that all mandatory
            properties are in the metadata of the item to export.
            The classe named above respects this.
        -->
        <resource xmlns="http://datacite.org/schema/kernel-4"
                xsi:schemaLocation="http://datacite.org/schema/kernel-4 http://schema.datacite.org/meta/kernel-4/metadata.xsd"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">

            <!--
                MANDATORY PROPERTIES
            -->

            <!--
                DataCite (1)
                Template Call for DOI identifier.
                Occ: 1
            -->
            <!--
                dc.identifier.uri may contain more than one DOI, e.g. if the
                repository contains an item that is published by a publishing 
                company as well. We have to ensure to use URIs of our prefix
                as primary identifiers only.
            -->
            <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='identifier' and @qualifier and (contains(., $prefix))]" />

            <!--
                DataCite (2)
                Add creator information. 
                Occ: 1-n
            -->
            <creators>
                <xsl:choose>
                    <!-- // DATASHARE - start (C5) build creators from dc.creator as well as
                         dc.contributor.author. DataShare holds author names in dc.creator, but
                         the vanilla DSpace-8 crosswalk that replaced DataShare's custom one only
                         read dc.contributor.author, so DOIs were registered with "(:unkn) unknown"
                         creators (issue #786). Only NON-EMPTY values switch on this branch, so a
                         present-but-blank field can neither suppress the fallback nor emit an
                         empty (schema-invalid) creatorName. -->
                    <xsl:when test="//dspace:field[@mdschema='dc' and ((@element='contributor' and @qualifier='author') or @element='creator') and normalize-space(.) != '']">
                        <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='contributor' and @qualifier='author' and normalize-space(.) != '']" />
                        <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='creator' and normalize-space(.) != '']" />
                    </xsl:when>
                    <!-- // DATASHARE - end (C5) -->
                    <xsl:otherwise>
                        <creator>
                            <creatorName>(:unkn) unknown</creatorName>
                        </creator>
                    </xsl:otherwise>
                </xsl:choose>
            </creators>

            <!--
                DataCite (3)
                Add Title information. 
		Occ: 1-n
            -->
            <titles>
                <xsl:choose>
                    <xsl:when test="//dspace:field[@mdschema='dc' and @element='title']">
                        <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='title']" />
                    </xsl:when>
                    <xsl:otherwise>
                        <title>(:unas) unassigned</title>
                    </xsl:otherwise>
                </xsl:choose>
            </titles>

            <!--
                DataCite (4)
                Add Publisher information from configuration above
                Occ: 1
                Use dc.publisher if it exists, use $publisher otherwise.
            -->
            <xsl:element name="publisher">
                <xsl:choose>
                    <xsl:when test="//dspace:field[@mdschema='dc' and @element='publisher']">
                        <xsl:value-of select="//dspace:field[@mdschema='dc' and @element='publisher'][1]" />
                    </xsl:when>
                    <xsl:otherwise>
                        <xsl:value-of select="$publisher" />
                    </xsl:otherwise>
                </xsl:choose>
            </xsl:element>

            <!--
                DataCite (5)
                Add PublicationYear information
                Occ: 1
                Format: YYYY
            -->
            <publicationYear>
                <xsl:choose>
                    <xsl:when test="//dspace:field[@mdschema='dc' and @element='date' and @qualifier='issued']">
                        <xsl:value-of select="substring(//dspace:field[@mdschema='dc' and @element='date' and @qualifier='issued'], 1, 4)" />
                    </xsl:when>
                    <xsl:when test="//dspace:field[@mdschema='dc' and @element='date' and @qualifier='available']">
                        <xsl:value-of select="substring(//dspace:field[@mdschema='dc' and @element='date' and @qualifier='available'], 1, 4)" />
                    </xsl:when>
                    <!-- // DATASHARE - start (C2) fall back to accessioned, then current year, instead of "0000" -->
                    <xsl:when test="//dspace:field[@mdschema='dc' and @element='date' and @qualifier='accessioned']">
                        <xsl:value-of select="substring(//dspace:field[@mdschema='dc' and @element='date' and @qualifier='accessioned'], 1, 4)" />
                    </xsl:when>
                    <xsl:when test="//dspace:field[@mdschema='dc' and @element='date']">
                        <xsl:value-of select="substring(//dspace:field[@mdschema='dc' and @element='date'], 1, 4)" />
                    </xsl:when>
                    <xsl:otherwise>
                        <xsl:value-of select="substring(string(current-date()), 1, 4)" />
                    </xsl:otherwise>
                    <!-- // DATASHARE - end (C2) -->
                </xsl:choose>
            </publicationYear>

            <!--
                OPTIONAL PROPERTIES
            -->

            <!--
                DataCite (6)
                Template Call for subjects.
                Occ: 0-n
                Format: open
                Attribute: subjectSchema (optional), schemeURI (optional)
            -->  
            <xsl:if test="//dspace:field[@mdschema='dc' and @element='subject']">
                <subjects>
                    <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='subject']" />
                </subjects>
            </xsl:if>

            <!--
                DataCite (7)
                Template Call for Contributors
                Occ: 0-n
                Format: personal name: family, given
                Required Attribute: contributorType - controlled list
            -->
            <!-- // DATASHARE - start (C7) Per the DataShare -> DataCite mapping spec:
                 dc.contributor (the depositor) maps to a ContactPerson contributor. The
                 vanilla DataManager/HostingInstitution contributors are removed: they are
                 not part of the DataShare mapping and, because the publisher/datamanager/
                 hostingInstitution parameters were left at the "My University" placeholder,
                 they rendered as a bogus "My University" contributor on every DOI.
                 dc.contributor.other is excluded here (and dropped entirely, as v5 did — see
                 DataCite (19)). Only emit <contributors> when there is at least one, so we
                 never produce an empty (schema-invalid) element. -->
            <xsl:if test="//dspace:field[@mdschema='dc' and @element='contributor' and not(@qualifier='author') and not(@qualifier='other') and normalize-space(.) != '']">
                <contributors>
                    <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='contributor' and not(@qualifier='author') and not(@qualifier='other') and normalize-space(.) != '']" />
                </contributors>
            </xsl:if>
            <!-- // DATASHARE - end (C7) -->

            <!--
                DataCite (8)
                Template Call for Dates
                Occ: 0-n
                Required Attribute: dataType - controlled list
            --> 
            <xsl:if test="//dspace:field[@mdschema='dc' and @element='date' and 
                        (@qualifier='accessioned' 
                         or @qualifier='available' 
                         or @qualifier='copyright' 
                         or @qualifier='created' 
                         or @qualifier='issued' 
                         or @qualifier='submitted'
                         or @qualifier='updated')]" >
                <xsl:element name="dates">
                    <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='date' and 
                        (@qualifier='accessioned' 
                         or @qualifier='available' 
                         or @qualifier='copyright' 
                         or @qualifier='created' 
                         or @qualifier='issued' 
                         or @qualifier='submitted'
                         or @qualifier='updated')]" />
                </xsl:element>
            </xsl:if>

            <!-- 
                DataCite (9)
                Templacte Call for Language
                Occ: 0-1
                Format: IETF BCP 47 or ISO 639-1
            -->
            <xsl:apply-templates select="(//dspace:field[@mdschema='dc' and @element='language' and (@qualifier='iso' or @qualifier='rfc3066')])[1]" />

            <!--
                DataCite (10)
                Template call for ResourceType
                DataCite allows the ResourceType to ouccre not more than once.
            -->
            <!--<xsl:apply-templates select="(//dspace:field[@mdschema='dc' and @element='type'])[1]" />-->
            <xsl:choose>
                <xsl:when test="(//dspace:field[@mdschema='dc' and @element='type'])[1]">
                    <xsl:apply-templates select="(//dspace:field[@mdschema='dc' and @element='type'])[1]" />
                </xsl:when>
                <xsl:otherwise>
                    <xsl:element name="resourceType">
                        <xsl:attribute name="resourceTypeGeneral">Other</xsl:attribute>
                        <xsl:value-of>Other</xsl:value-of>
                    </xsl:element>
                </xsl:otherwise>
            </xsl:choose>

            <!-- 
                DataCite (11)
                Add alternativeIdentifiers.
                This element is important as it is used to recognize for which
                DSpace object a DOI is reserved for.
                See the primary identifier for which the doi is registered.
                Occ: 0-n
                Required Attribute: alternateIdentifierType (free format)
            -->
            <xsl:if test="//dspace:field[@mdschema='dc' and @element='identifier' and @qualifier and not(contains(., $prefix))]">
                <xsl:element name="alternateIdentifiers">
                    <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='identifier' and @qualifier and not(contains(., $prefix))]" />
                </xsl:element>
            </xsl:if>

            <!--
                DataCite (12)
                Add relatedIdentifier.
                DataCite requires a relatedIdentifierType, but we do not know which
                type of identifier is part of the dc.relation.* fields within DSpace.
                Skip the related identifier until we find a proper solution.
            -->

            <!--
                DataCite (13)
                Add sizes.
            -->
            <xsl:if test="//dspace:field[@mdschema='dc' and @element='format' and @qualifier='extent']">             
                <xsl:element name="sizes">
                    <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='format' and @qualifier='extent']" />      
                </xsl:element>
            </xsl:if>

            <!-- DataCite (14)
                 Add formats.
            -->
            <xsl:if test="//dspace:field[@mdschema='dc' and @element='format'][not(@qualifier='extent')]">
                <xsl:element name="formats">
                    <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='format'][not(@qualifier='extent')]" />       
                </xsl:element>
            </xsl:if>

            <!--
                 DataCite (15)
                 Add version.
                 As we currently do not link versions as related identifier, we skip
                the version information too.
            -->

            <!--
                DataCite (16)
                Rights.
                Occ: 0-1
            -->
            <xsl:if test="//dspace:field[@mdschema='dc' and @element='rights']">
                <xsl:element name="rightsList">
                    <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='rights']" />
                </xsl:element>
            </xsl:if>

            <!--
                DataCite (17)
                Add descriptions.
                Occ: 0-n
                Required Attribute: descriptionType - controlled list
            -->
            <xsl:if test="//dspace:field[@mdschema='dc' and @element='description' and (@qualifier='abstract' or @qualifier='tableofcontents' or not(@qualifier))]">
                <xsl:element name="descriptions">
                    <xsl:apply-templates select="//dspace:field[@mdschema='dc' and @element='description' and (@qualifier='abstract' or @qualifier='tableofcontents' or not(@qualifier))]" />
                </xsl:element>
            </xsl:if>
            
            <!--
                DataCite (18)
                GeoLocation
                DSpace currently doesn't store geolocations.
            -->
            <!--
                DataCite (19)
                FundingReference
                // DATASHARE (C8 removed): dc.contributor.other holds a free-text "funder"
                field, but production DataShare v5 never emitted fundingReferences (0 of the
                repository's ~7800 DOIs carry one) and the field is dirty (values such as
                "Self-funded", "Other", the depositing institution, or bare grant numbers).
                To stay faithful to the pre-migration output we drop it entirely, exactly as
                v5 did, rather than register incorrect funder metadata with DataCite.
            -->
            <!--
                DataCite (20)
                RelatedItem
            -->
        </resource>
    </xsl:template>


    <!-- Add doi identifier information. -->
    <!--
        dc.identifier.uri may contain more than one DOI, e.g. if the
        repository contains an item that is published by a publishing 
        company as well. We have to ensure to use URIs of our prefix
        as primary identifiers only.
    -->
    <xsl:template match="dspace:field[@mdschema=$mdSchema and @element=$mdElement and (contains(., $prefix))]">
        <xsl:if test="(($mdQualifier and $mdQualifier != '') and @qualifier=$mdQualifier) or ((not($mdQualifier) or $mdQualifier = '') and not(@qualifier))">
            <identifier identifierType="DOI">
                <xsl:if test="starts-with(string(text()), 'https://doi.org/')">
                    <xsl:value-of select="substring(., 17)"/>
                </xsl:if>
                <xsl:if test="starts-with(string(text()), 'http://dx.doi.org/')">
                    <xsl:value-of select="substring(., 19)"/>
                </xsl:if>
            </identifier>
        </xsl:if>
    </xsl:template>

    <!-- DataCite (2) :: Creator (dc.contributor.author) -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='contributor' and @qualifier='author']">
        <creator>
            <creatorName>
                <xsl:value-of select="." />
            </creatorName>
        </creator>
    </xsl:template>

    <!-- DataCite (2) :: Creator (dc.creator) -->
    <!-- // DATASHARE - start (C5) DataShare holds author names in dc.creator; map them to
         <creator> exactly like dc.contributor.author so migrated items keep their authors
         in the registered DataCite metadata instead of "(:unkn) unknown" (issue #786). -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='creator']">
        <creator>
            <creatorName>
                <xsl:value-of select="." />
            </creatorName>
        </creator>
    </xsl:template>
    <!-- // DATASHARE - end (C5) -->

    <!-- DataCite (3) :: Title -->
    <xsl:template match="dspace:field[@mdschema='dc' and @element='title']">
        <xsl:element name="title">
            <xsl:attribute name="xml:lang"><xsl:value-of select="translate(@lang, '_', '-')" /></xsl:attribute>
            <xsl:if test="@qualifier='alternative'">
                <xsl:attribute name="xml:lang"><xsl:value-of select="translate(@lang, '_', '-')" /></xsl:attribute>
                <xsl:attribute name="titleType">AlternativeTitle</xsl:attribute>
            </xsl:if>
            <!-- DSpace doesn't include a dc.title.subtitle nor a
                 dc.title.translated. If necessary, please create those in the 
                 metadata field registry. -->
            <xsl:if test="@qualifier='subtitle'">
                <xsl:attribute name="xml:lang"><xsl:value-of select="translate(@lang, '_', '-')" /></xsl:attribute>
                <xsl:attribute name="titleType">Subtitle</xsl:attribute>
            </xsl:if>
            <xsl:if test="@qualifier='translated'">
                <xsl:attribute name="xml:lang"><xsl:value-of select="translate(@lang, '_', '-')" /></xsl:attribute>
                <xsl:attribute name="titleType">TranslatedTitle</xsl:attribute>
            </xsl:if>
            <xsl:value-of select="." />
        </xsl:element>
    </xsl:template>

    <!--
        DataCite (6), DataCite (6.1)
        Adds subject and subjectScheme information

        "This term is intended to be used with non-literal values as defined in the
        DCMI Abstract Model (http://dublincore.org/documents/abstract-model/).
        As of December 2007, the DCMI Usage Board is seeking a way to express
        this intention with a formal range declaration."
        (http://dublincore.org/documents/dcmi-terms/#terms-subject)
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='subject']">
        <xsl:element name="subject">
            <xsl:attribute name="xml:lang"><xsl:value-of select="translate(@lang, '_', '-')" /></xsl:attribute>
            <xsl:if test="@qualifier">
                <xsl:attribute name="subjectScheme"><xsl:value-of select="@qualifier" /></xsl:attribute>
            </xsl:if>
            <xsl:value-of select="." />
        </xsl:element>
    </xsl:template>

    <!--
        DataCite (7), DataCite (7.1)
        Adds contributor and contributorType information
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='contributor'][not(@qualifier='author')]">
        <xsl:choose>
            <xsl:when test="@qualifier='editor'"> 
                <xsl:element name="contributor">
                    <xsl:attribute name="contributorType">Editor</xsl:attribute>
                    <contributorName>
                        <xsl:value-of select="." />
                    </contributorName>
                </xsl:element>
            </xsl:when>
            <xsl:when test="@qualifier='advisor'"> 
                <xsl:element name="contributor">
                    <xsl:attribute name="contributorType">RelatedPerson</xsl:attribute>
                    <contributorName>
                        <xsl:value-of select="." />
                    </contributorName>
                </xsl:element>
            </xsl:when>
            <xsl:when test="@qualifier='illustrator'"> 
                <xsl:element name="contributor">
                    <xsl:attribute name="contributorType">Other</xsl:attribute>
                    <contributorName>
                        <xsl:value-of select="." />
                    </contributorName>
                </xsl:element>
            </xsl:when>
            <!-- // DATASHARE - start (C7) an unqualified dc.contributor is the depositor and
                 maps to ContactPerson (was "Other"). dc.contributor.other is dropped (as v5
                 did) and is intentionally not matched here. -->
            <xsl:when test="not(@qualifier)">
                <xsl:element name="contributor">
                    <xsl:attribute name="contributorType">ContactPerson</xsl:attribute>
                    <contributorName>
                        <xsl:value-of select="." />
                    </contributorName>
                </xsl:element>
            </xsl:when>
            <!-- // DATASHARE - end (C7) -->
        </xsl:choose>
    </xsl:template>

    <!--
        DataCite (8), DataCite (8.1)
        Adds Date and dateType information
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='date' and 
                        (@qualifier='accessioned' 
                         or @qualifier='available' 
                         or @qualifier='copyright' 
                         or @qualifier='created' 
                         or @qualifier='issued' 
                         or @qualifier='submitted'
                         or @qualifier='updated')]">
    	<xsl:if test="@qualifier='accessioned' 
                        or @qualifier='available' 
                        or @qualifier='copyright' 
                        or @qualifier='created' 
                        or @qualifier='issued' 
                        or @qualifier='submitted'
                        or @qualifier='updated'">
            <xsl:element name="date">
                <xsl:if test="@qualifier='accessioned'">
                    <xsl:attribute name="dateType">Accepted</xsl:attribute>
                </xsl:if>
                <xsl:if test="@qualifier='available'">
                    <xsl:attribute name="dateType">Available</xsl:attribute>
                </xsl:if>
                <xsl:if test="@qualifier='copyright'">
                    <xsl:attribute name="dateType">Copyrighted</xsl:attribute>
                </xsl:if>
                <xsl:if test="@qualifier='created'">
                    <xsl:attribute name="dateType">Created</xsl:attribute>
                </xsl:if>
                <xsl:if test="@qualifier='issued'">
                    <xsl:attribute name="dateType">Issued</xsl:attribute>
                </xsl:if>
                <!-- DSpace recommends to use dc.date.submitted for theses and/or
                     dissertations. DataCite uses submitted for the "date the 
                     creator submits the resource to the publisher". -->
                <xsl:if test="@qualifier='submitted'">
                    <xsl:attribute name="dateType">Submitted</xsl:attribute>
                </xsl:if>
                <xsl:if test="@qualifier='updated'">
                    <xsl:attribute name="dateType">Updated</xsl:attribute>
                </xsl:if>
	    	<xsl:value-of select="substring(., 1, 10)" />
            </xsl:element>
	</xsl:if>
    </xsl:template>

    <!--
        DataCite (9)
        Adds Language information
        Transforming the language flags according to IETF BCP 47 or ISO 639-1
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='language' and (@qualifier='iso' or @qualifier='rfc3066')][1]">
        <xsl:element name="language">
            <xsl:choose>
                <xsl:when test="contains(string(text()), '_')">
                    <xsl:value-of select="translate(string(text()), '_', '-')"/>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:value-of select="string(text())"/>
                </xsl:otherwise>
            </xsl:choose>
        </xsl:element>
    </xsl:template>

    <!--
        DataCite (10), DataCite (10.1)
        Adds resourceType and resourceTypeGeneral information
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='type'][1]">
        <xsl:element name="resourceType">
            <!-- // DATASHARE - start (C6) match dc.type case-insensitively and add the three
                 DataShare-specific type values that the vanilla list omits ("moving image",
                 "interactive resource", "sound"). After the migration dc.type is stored
                 lower-case (e.g. "dataset"), which the previous case-sensitive comparison
                 downgraded to "Other" (issue #786: "Dataset" became "Other"); the same happened
                 to DataShare's "sound"/"moving image"/"interactive resource" items. The verified
                 DataShare vocabulary is image/dataset/sound/software/text/moving image/interactive
                 resource; the remaining rows are inherited from vanilla and unused by DataShare. -->
            <xsl:variable name="typeLower" select="lower-case(normalize-space(.))" />
            <xsl:attribute name="resourceTypeGeneral">
                <xsl:choose>
                    <xsl:when test="$typeLower='animation'">Audiovisual</xsl:when>
                    <xsl:when test="$typeLower='moving image'">Audiovisual</xsl:when>
                    <xsl:when test="$typeLower='article'">JournalArticle</xsl:when>
                    <xsl:when test="$typeLower='book'">Book</xsl:when>
                    <xsl:when test="$typeLower='book chapter'">BookChapter</xsl:when>
                    <xsl:when test="$typeLower='dataset'">Dataset</xsl:when>
                    <xsl:when test="$typeLower='learning object'">InteractiveResource</xsl:when>
                    <xsl:when test="$typeLower='interactive resource'">InteractiveResource</xsl:when>
                    <xsl:when test="$typeLower='image'">Image</xsl:when>
                    <xsl:when test="$typeLower='image, 3-d'">Image</xsl:when>
                    <xsl:when test="$typeLower='map'">Model</xsl:when>
                    <xsl:when test="$typeLower='musical score'">Other</xsl:when>
                    <xsl:when test="$typeLower='plan or blueprint'">Model</xsl:when>
                    <xsl:when test="$typeLower='preprint'">Preprint</xsl:when>
                    <xsl:when test="$typeLower='presentation'">Other</xsl:when>
                    <xsl:when test="$typeLower='recording, acoustical'">Sound</xsl:when>
                    <xsl:when test="$typeLower='recording, musical'">Sound</xsl:when>
                    <xsl:when test="$typeLower='recording, oral'">Sound</xsl:when>
                    <xsl:when test="$typeLower='sound'">Sound</xsl:when>
                    <xsl:when test="$typeLower='software'">Software</xsl:when>
                    <xsl:when test="$typeLower='technical report'">Report</xsl:when>
                    <xsl:when test="$typeLower='thesis'">Dissertation</xsl:when>
                    <xsl:when test="$typeLower='video'">Audiovisual</xsl:when>
                    <xsl:when test="$typeLower='working paper'">Text</xsl:when>
                    <xsl:otherwise>Other</xsl:otherwise>
                </xsl:choose>
            </xsl:attribute>
            <!-- // DATASHARE - end (C6) -->
            <xsl:value-of select="." />
        </xsl:element>
    </xsl:template>

    <!--
        DataCite (11), DataCite (11.1)
        Adds AlternativeIdentifier and alternativeIdentifierType information
        Adds all identifiers except the doi.

        This element is important as it is used to recognize for which DSpace
        objet a DOI is reserved for. The DataCiteConnector will test all
        AlternativeIdentifiers by using HandleManager.
        resolveUrlToHandle(context, altId) until one is recognized or all have
        been tested.
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='identifier' and @qualifier and not(contains(., $prefix))]">
        <xsl:element name="alternateIdentifier">
            <xsl:if test="@qualifier">
                <xsl:attribute name="alternateIdentifierType"><xsl:value-of select="@qualifier" /></xsl:attribute>
            </xsl:if>
            <xsl:value-of select="." />
        </xsl:element>
    </xsl:template>

    <!--
        DataCite (12), DataCite (12.1)
        Adds RelatedIdentifier and relatedIdentifierType information
        DataCite requires a relatedIdentifierType, but we do not know which
        type of identifier is part of the dc.relation.* fields within DSpace.
        Skip the related identifier until we find a proper solution.
    -->

    <!--
        DataCite (13)
        Adds Size information
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='format' and @qualifier='extent']">
        <xsl:element name="size">
            <xsl:value-of select="." />
        </xsl:element>
    </xsl:template>

    <!--
        DataCite (14)
        Adds Format information
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='format'][not(@qualifier='extent')]">
        <xsl:element name="format">
            <xsl:value-of select="." />
        </xsl:element>
    </xsl:template>
    
    <!--
        DataCite (15)
        Version information.
        As we currently do not link versions as related identifier, we skip
        the version information too.
    -->

    <!--
        DataCite (16)
        Adds Rights information.
        // DATASHARE - start (C3, C4)
          C3 — when @qualifier='uri', emit <rights rightsURI="<value>"/>
          C4 — when value contains "Creative Commons Attribution 4.0", add SPDX
               rightsIdentifier=CC-BY-4.0 and related attributes.
        // DATASHARE - end
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='rights']">
        <xsl:choose>
            <!-- // DATASHARE - start (C3) -->
            <xsl:when test="@qualifier='uri'">
                <xsl:element name="rights">
                    <xsl:attribute name="rightsURI">
                        <xsl:value-of select="." />
                    </xsl:attribute>
                </xsl:element>
            </xsl:when>
            <!-- // DATASHARE - end (C3) -->
            <!-- // DATASHARE - start (C4) -->
            <xsl:when test="contains(., 'Creative Commons Attribution 4.0')">
                <xsl:element name="rights">
                    <xsl:attribute name="rightsURI">https://creativecommons.org/licenses/by/4.0/</xsl:attribute>
                    <xsl:attribute name="rightsIdentifier">CC-BY-4.0</xsl:attribute>
                    <xsl:attribute name="rightsIdentifierScheme">SPDX</xsl:attribute>
                    <xsl:attribute name="schemeURI">https://spdx.org/licenses/</xsl:attribute>
                    <xsl:value-of select="." />
                </xsl:element>
            </xsl:when>
            <!-- // DATASHARE - end (C4) -->
            <xsl:otherwise>
                <xsl:element name="rights">
                    <xsl:value-of select="." />
                </xsl:element>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <!--
        DataCite (17)
        Description
    -->
    <xsl:template match="//dspace:field[@mdschema='dc' and @element='description' and (@qualifier='abstract' or @qualifier='tableofcontents' or not(@qualifier))]">
        <xsl:element name="description">
            <xsl:attribute name="xml:lang"><xsl:value-of select="translate(@lang, '_', '-')" /></xsl:attribute>
            <xsl:attribute name="descriptionType">
           	<xsl:choose>
                    <xsl:when test="@qualifier='abstract'">Abstract</xsl:when>
                    <xsl:when test="@qualifier='tableofcontents'">TableOfContents</xsl:when>
               	    <xsl:otherwise>Other</xsl:otherwise>
                </xsl:choose>
            </xsl:attribute>
            <xsl:value-of select="." />
        </xsl:element>
    </xsl:template>

</xsl:stylesheet>
<!-- // DATASHARE - end -->

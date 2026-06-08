--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------------------------------------------------------

-- Datashare specific metadata fields for the new license functionality.

-----------------------------------------------------------------------------------------------------------------------------------

-- Insert into ds.license.dropdown-value 
INSERT INTO metadatafieldregistry (metadata_schema_id, element, qualifier)
  SELECT (SELECT metadata_schema_id FROM metadataschemaregistry WHERE short_id='ds'), 'license', 'dropdown-value'
    WHERE NOT EXISTS (SELECT metadata_field_id,element,qualifier FROM metadatafieldregistry WHERE element = 'license' AND qualifier='dropdown-value' AND metadata_schema_id = (SELECT metadata_schema_id FROM metadataschemaregistry WHERE short_id='ds'));

-- Insert into ds.license.dropdown-value 
INSERT INTO metadatafieldregistry (metadata_schema_id, element, qualifier)
  SELECT (SELECT metadata_schema_id FROM metadataschemaregistry WHERE short_id='ds'), 'license', 'rights-text'
    WHERE NOT EXISTS (SELECT metadata_field_id,element,qualifier FROM metadatafieldregistry WHERE element = 'license' AND qualifier='rights-text' AND metadata_schema_id = (SELECT metadata_schema_id FROM metadataschemaregistry WHERE short_id='ds'));
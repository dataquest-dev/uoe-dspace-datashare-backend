--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------------------------------------------------------

-- Datashare specific metadata fields for the new Funder functionality.

-----------------------------------------------------------------------------------------------------------------------------------

-- Insert into ds.license.dropdown-value 

INSERT INTO metadatafieldregistry (metadata_schema_id, element, qualifier)
  SELECT ms.metadata_schema_id, 'funder', 'dropdown-value'
  FROM metadataschemaregistry ms
  WHERE ms.short_id = 'ds'
    AND NOT EXISTS (
      SELECT 1 
      FROM metadatafieldregistry 
      WHERE element = 'funder' 
      AND qualifier = 'dropdown-value'
      AND metadata_schema_id = ms.metadata_schema_id);

-- Insert into ds.license.rights-text 
INSERT INTO metadatafieldregistry (metadata_schema_id, element, qualifier)
  SELECT ms.metadata_schema_id, 'funder', 'text-value'
  FROM metadataschemaregistry ms
  WHERE ms.short_id = 'ds'
    AND NOT EXISTS (
      SELECT 1 
      FROM metadatafieldregistry 
      WHERE element = 'funder' 
      AND qualifier = 'text-value'
      AND metadata_schema_id = ms.metadata_schema_id);
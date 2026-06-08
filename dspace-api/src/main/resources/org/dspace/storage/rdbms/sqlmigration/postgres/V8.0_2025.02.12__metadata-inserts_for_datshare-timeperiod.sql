--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------------------------------------------------------

-- Datashare specific metadata fields for the timeperiod form functionality.

-----------------------------------------------------------------------------------------------------------------------------------

-- Insert into ds.timeperiod.start-date 
INSERT INTO metadatafieldregistry (metadata_schema_id, element, qualifier)
  SELECT (SELECT metadata_schema_id FROM metadataschemaregistry WHERE short_id='ds'), 'timeperiod', 'start-date'
    WHERE NOT EXISTS (SELECT metadata_field_id,element,qualifier FROM metadatafieldregistry WHERE element = 'timeperiod' AND qualifier='start-date' AND metadata_schema_id = (SELECT metadata_schema_id FROM metadataschemaregistry WHERE short_id='ds'));

-- Insert into ds.timeperiod.end-date
INSERT INTO metadatafieldregistry (metadata_schema_id, element, qualifier)
  SELECT (SELECT metadata_schema_id FROM metadataschemaregistry WHERE short_id='ds'), 'timeperiod', 'end-date'
    WHERE NOT EXISTS (SELECT metadata_field_id,element,qualifier FROM metadatafieldregistry WHERE element = 'timeperiod' AND qualifier='end-date' AND metadata_schema_id = (SELECT metadata_schema_id FROM metadataschemaregistry WHERE short_id='ds'));
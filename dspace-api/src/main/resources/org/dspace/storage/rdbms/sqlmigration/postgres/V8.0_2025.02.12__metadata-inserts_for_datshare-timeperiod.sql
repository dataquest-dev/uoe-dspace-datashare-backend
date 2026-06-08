--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------------------------------------------------------

-- Datashare specific metadata fields for the timeperiod form functionality.
--
-- These ds.* fields are legacy: the Datashare submission now writes to dc.* directly and the 'ds' schema is removed
-- by V8.0_2025.07.20__drop-datashare-ds-schema.sql. This migration must therefore be a no-op when the 'ds' schema
-- does not exist (e.g. on a clean install): selecting the schema id with a correlated FROM ... WHERE short_id='ds'
-- yields zero rows so nothing is inserted, instead of the previous "SELECT (SELECT metadata_schema_id ...)" form
-- which returned NULL and failed the metadata_schema_id NOT-NULL constraint, aborting the whole Flyway migration.
-- Same safe pattern as V8.0_2025.04.11__metadata-inserts_for_datshare-funder.sql. The 'ds' schema is never created.

-----------------------------------------------------------------------------------------------------------------------------------

-- Insert into ds.timeperiod.start-date (only if the 'ds' schema exists)
INSERT INTO metadatafieldregistry (metadata_schema_id, element, qualifier)
  SELECT ms.metadata_schema_id, 'timeperiod', 'start-date'
  FROM metadataschemaregistry ms
  WHERE ms.short_id = 'ds'
    AND NOT EXISTS (SELECT 1 FROM metadatafieldregistry
                    WHERE element = 'timeperiod' AND qualifier = 'start-date'
                    AND metadata_schema_id = ms.metadata_schema_id);

-- Insert into ds.timeperiod.end-date (only if the 'ds' schema exists)
INSERT INTO metadatafieldregistry (metadata_schema_id, element, qualifier)
  SELECT ms.metadata_schema_id, 'timeperiod', 'end-date'
  FROM metadataschemaregistry ms
  WHERE ms.short_id = 'ds'
    AND NOT EXISTS (SELECT 1 FROM metadatafieldregistry
                    WHERE element = 'timeperiod' AND qualifier = 'end-date'
                    AND metadata_schema_id = ms.metadata_schema_id);

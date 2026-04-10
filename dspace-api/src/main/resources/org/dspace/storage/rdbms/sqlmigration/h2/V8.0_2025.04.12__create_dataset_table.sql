--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------
-- CREATE dataset table for DataShare DatashareDataset entity
-----------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS dataset (
    uuid UUID NOT NULL,
    id INTEGER,
    item_id UUID NOT NULL,
    file_name VARCHAR(255),
    checksum VARCHAR(255),
    checksum_algorithm VARCHAR(255),
    CONSTRAINT dataset_pkey PRIMARY KEY (uuid),
    CONSTRAINT dataset_uuid_fkey FOREIGN KEY (uuid) REFERENCES dspaceobject(uuid),
    CONSTRAINT dataset_item_id_fkey FOREIGN KEY (item_id) REFERENCES item(uuid)
);

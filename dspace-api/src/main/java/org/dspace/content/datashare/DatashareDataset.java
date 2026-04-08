/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.datashare;

import org.dspace.content.DSpaceObject;
import org.dspace.content.DSpaceObjectLegacySupport;
import org.dspace.content.Item;
import org.dspace.content.datashare.service.DatashareDatasetService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.core.Constants;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * DataShare item dataset. That is a zip file that contains all item bitstreams.
 */
@Entity
@Table(name = "dataset")
public class DatashareDataset extends DSpaceObject implements DSpaceObjectLegacySupport {

    @Column(name = "id", insertable = false, updatable = false)
    private Integer legacyId;

    @OneToOne(optional = false)
    @JoinColumn(name = "item_id")
    private Item item = null;

    @Column(name = "file_name")
    private String fileName = null;

    @Column(name = "checksum")
    private String checksum;

    @Column(name = "checksum_algorithm")
    private String checkSumAlgorithm;

    @Transient
    private transient DatashareDatasetService datashareDatasetService;

    public DatashareDataset() {

    }

    public Item getItem() {
        return item;
    }

    public void setItem(Item item) {
        this.item = item;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getCheckSumAlgorithm() {
        return checkSumAlgorithm;
    }

    public void setCheckSumAlgorithm(String checkSumAlgorithm) {
        this.checkSumAlgorithm = checkSumAlgorithm;
    }

    public void setLegacyId(Integer legacyId) {
        this.legacyId = legacyId;
    }

    @Override
    public Integer getLegacyId() {
        return this.legacyId;
    }

    @Override
    public int getType() {
        return Constants.DATASHARE_DATASET;
    }

    @Override
    public String getName() {
        return fileName;
    }

    public DatashareDatasetService getDatashareDatasetService() {
        if (datashareDatasetService == null) {
            datashareDatasetService = ContentServiceFactory.getInstance().getDatashareDatasetService();
        }
        return datashareDatasetService;
    }

}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit.step.datashare;

import java.io.File;
import java.io.FileInputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.MetadataValueRest;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.RemoveOperation;
import org.dspace.app.rest.model.step.DataDescribe;
import org.dspace.app.rest.submit.AbstractProcessingStep;
import org.dspace.app.rest.submit.SubmissionService;
import org.dspace.app.rest.submit.factory.PatchOperationFactory;
import org.dspace.app.rest.submit.factory.impl.PatchOperation;
import org.dspace.app.util.DCInput;
import org.dspace.app.util.DCInputSet;
import org.dspace.app.util.DCInputsReader;
import org.dspace.app.util.DCInputsReaderException;
import org.dspace.app.util.SubmissionStepConfig;
import org.dspace.content.InProgressSubmission;
import org.dspace.content.MetadataField;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.MetadataFieldService;
import org.dspace.content.service.MetadataValueService;
import org.dspace.core.Context;
import org.dspace.core.Utils;
import org.dspace.license.factory.LicenseServiceFactory;
import org.dspace.license.service.CreativeCommonsService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;

/**
 * Datashare license step for DSpace Spring Rest. Expose and allow patching of
 * the in
 * progress submission metadata. It is
 * configured via the config/submission-forms.xml file.
 * This is basically a copy of the code for DescribeStep extended
 * to provide the Datashare license functionality.
 *
 *
 * @author Luigi Andrea Pascarelli (luigiandrea.pascarelli at 4science.it)
 * @author Andrea Bollini (andrea.bollini at 4science.it)
 * @author John Pinto (Research Data Service, Information Services Group,
 *         University of Edinburgh)
 */
public class DatashareLicenseStep extends AbstractProcessingStep {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(DatashareLicenseStep.class);

    // Input reader for form configuration
    private DCInputsReader inputReader;
    // Configuration service
    private final ConfigurationService configurationService = DSpaceServicesFactory.getInstance()
            .getConfigurationService();

    private CreativeCommonsService creativeCommonsService = LicenseServiceFactory.getInstance()
            .getCreativeCommonsService();

    private ItemService itemService = ContentServiceFactory.getInstance().getItemService();

    private MetadataFieldService metadataFieldService = ContentServiceFactory.getInstance().getMetadataFieldService();

    private MetadataValueService metadataValueService = ContentServiceFactory.getInstance().getMetadataValueService();


    public DatashareLicenseStep() throws DCInputsReaderException {
        inputReader = new DCInputsReader();
    }

    public String CREATIVE_COMMONS_BY_LICENCE_FILE = configurationService.getProperty("dspace.dir")
            + File.separator + "config" + File.separator + "cc-by.license";

    @Override
    public DataDescribe getData(SubmissionService submissionService, InProgressSubmission obj,
            SubmissionStepConfig config) {
        DataDescribe data = new DataDescribe();
        try {
            DCInputSet inputConfig = inputReader.getInputsByFormName(config.getId());
            readField(obj, config, data, inputConfig);
        } catch (DCInputsReaderException e) {
            log.error(e.getMessage(), e);
        }
        return data;
    }

    private void readField(InProgressSubmission obj, SubmissionStepConfig config, DataDescribe data,
            DCInputSet inputConfig) throws DCInputsReaderException {
        String documentTypeValue = "";
        List<MetadataValue> documentType = itemService.getMetadataByMetadataString(obj.getItem(),
                configurationService.getProperty("submit.type-bind.field", "dc.type"));
        if (documentType.size() > 0) {
            documentTypeValue = documentType.get(0).getValue();
        }

        // Get list of all field names (including qualdrop names) allowed for this
        // dc.type
        List<String> allowedFieldNames = inputConfig.populateAllowedFieldNames(documentTypeValue);

        // Loop input rows and process submitted metadata
        for (DCInput[] row : inputConfig.getFields()) {
            for (DCInput input : row) {
                List<String> fieldsName = new ArrayList<String>();
                if (input.isQualdropValue()) {
                    for (Object qualifier : input.getPairs()) {
                        fieldsName.add(input.getFieldName() + "." + (String) qualifier);
                    }
                } else {
                    String fieldName = input.getFieldName();
                    if (fieldName != null) {
                        fieldsName.add(fieldName);
                    }
                }

                for (String fieldName : fieldsName) {
                    List<MetadataValue> mdv = itemService.getMetadataByMetadataString(obj.getItem(),
                            fieldName);
                    for (MetadataValue md : mdv) {
                        MetadataValueRest dto = new MetadataValueRest();
                        dto.setAuthority(md.getAuthority());
                        dto.setConfidence(md.getConfidence());
                        dto.setLanguage(md.getLanguage());
                        dto.setPlace(md.getPlace());
                        dto.setValue(md.getValue());

                        String[] metadataToCheck = Utils.tokenize(md.getMetadataField().toString());
                        if (data.getMetadata().containsKey(
                                Utils.standardize(metadataToCheck[0], metadataToCheck[1], metadataToCheck[2], "."))) {
                            // If field is allowed by type bind, add value to existing field set, otherwise
                            // remove
                            // all values for this field
                            if (allowedFieldNames.contains(fieldName)) {
                                data.getMetadata()
                                        .get(Utils.standardize(md.getMetadataField().getMetadataSchema().getName(),
                                                md.getMetadataField().getElement(),
                                                md.getMetadataField().getQualifier(),
                                                "."))
                                        .add(dto);
                            } else {
                                data.getMetadata().remove(Utils.standardize(metadataToCheck[0], metadataToCheck[1],
                                        metadataToCheck[2], "."));
                            }
                        } else {
                            // Add values only if allowed by type bind
                            if (allowedFieldNames.contains(fieldName)) {
                                List<MetadataValueRest> listDto = new ArrayList<>();
                                listDto.add(dto);
                                data.getMetadata()
                                        .put(Utils.standardize(md.getMetadataField().getMetadataSchema().getName(),
                                                md.getMetadataField().getElement(),
                                                md.getMetadataField().getQualifier(),
                                                "."), listDto);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void doPatchProcessing(Context context, HttpServletRequest currentRequest, InProgressSubmission source,
            Operation op, SubmissionStepConfig stepConf) throws Exception {
        String[] pathParts = op.getPath().substring(1).split("/");
        DCInputSet inputConfig = inputReader.getInputsByFormName(stepConf.getId());
        if ("remove".equals(op.getOp()) && pathParts.length < 3) {
            // manage delete all step fields
            String[] path = op.getPath().substring(1).split("/", 3);
            String configId = path[1];
            List<String> fieldsName = getInputFieldsName(inputConfig, configId);
            for (String fieldName : fieldsName) {
                String fieldPath = op.getPath() + "/" + fieldName;
                Operation fieldRemoveOp = new RemoveOperation(fieldPath);
                PatchOperation<MetadataValueRest> patchOperation = new PatchOperationFactory()
                        .instanceOf(DESCRIBE_STEP_METADATA_OPERATION_ENTRY, fieldRemoveOp.getOp());
                patchOperation.perform(context, currentRequest, source, fieldRemoveOp);
            }
        } else {
            PatchOperation<MetadataValueRest> patchOperation = new PatchOperationFactory()
                    .instanceOf(DESCRIBE_STEP_METADATA_OPERATION_ENTRY, op.getOp());
            String[] split = patchOperation.getAbsolutePath(op.getPath()).split("/");
            if (inputConfig.isFieldPresent(split[0])) {
                patchOperation.perform(context, currentRequest, source, op);
            } else {
                throw new UnprocessableEntityException("The field " + split[0] + " is not present in section "
                        + inputConfig.getFormName());
            }
        }

        if ("remove".equals(op.getOp()) || "add".equals(op.getOp()) || "replace".equals(op.getOp())) {
            List<MetadataValue> metadataValues = source.getItem().getMetadata();

            MetadataValue dcRightsMetadataValue = null;
            MetadataValue dsLicenseDropdownValueMetadataValue = null;
            MetadataValue dsRightsTextMetadataValue = null;
            MetadataField dcRightsMetadataField = metadataFieldService.findByElement(context, "dc", "rights", "");
            MetadataField dsLicenseDropdownValueField = metadataFieldService.findByElement(context, "ds", "license",
                    "dropdown-value");
            MetadataField dsLicenseRightsTextField = metadataFieldService.findByElement(context, "ds", "license",
                    "rights-text");
            for (MetadataValue mv : metadataValues) {
                log.info("mv.getMetadataField().getID(): " + mv.getMetadataField().getID());

                if (dcRightsMetadataField != null && mv.getMetadataField().getID().equals(dcRightsMetadataField.getID())) {
                    dcRightsMetadataValue = mv;
                    log.info("dcRightsMetadataValue: " + dcRightsMetadataValue.getValue());
                } else if (dsLicenseDropdownValueField != null
                        && mv.getMetadataField().getID().equals(dsLicenseDropdownValueField.getID())) {
                    dsLicenseDropdownValueMetadataValue = mv;
                    log.info("dsLicenseDropdownValueMetadataValue: " + dsLicenseDropdownValueMetadataValue.getValue());
                } else if (dsLicenseRightsTextField != null
                        && mv.getMetadataField().getID().equals(dsLicenseRightsTextField.getID())) {
                    dsRightsTextMetadataValue = mv;
                    log.info("dsRightsTextMetadataValue: " + dsRightsTextMetadataValue.getValue());
                }
            }
            log.info("dcRightsMetadataValue: " + dcRightsMetadataValue);
            log.info("dsRightsTextMetadataValue: " + dsRightsTextMetadataValue);
            log.info("dsLicenseDropdownValueMetadataValue: " + dsLicenseDropdownValueMetadataValue);

            MetadataField metadataField = metadataFieldService.findByElement(context, "dc", "rights", "");
            if (dcRightsMetadataValue == null) {
                dcRightsMetadataValue = metadataValueService.create(context, source.getItem(), metadataField);
            }
            if (dsLicenseDropdownValueMetadataValue != null
                    && !dsLicenseDropdownValueMetadataValue.getValue().equals("Other")) {
                dcRightsMetadataValue.setValue(dsLicenseDropdownValueMetadataValue.getValue());
                metadataValueService.update(context, dcRightsMetadataValue);
                if (dsRightsTextMetadataValue != null) {
                    deleteItemMetadataValue(context, source, dsRightsTextMetadataValue);
                }
            }
            if (dsLicenseDropdownValueMetadataValue != null
                    && dsLicenseDropdownValueMetadataValue.getValue().equals("Other")) {
                String rightsText = dsRightsTextMetadataValue == null
                        || StringUtils.isBlank(dsRightsTextMetadataValue.getValue()) ? ""
                                : dsRightsTextMetadataValue.getValue();

                dcRightsMetadataValue.setValue(rightsText);
                metadataValueService.update(context, dcRightsMetadataValue);
            }

            if (dsLicenseDropdownValueMetadataValue == null) {

                if (dsRightsTextMetadataValue != null) {
                    deleteItemMetadataValue(context, source, dsRightsTextMetadataValue);
                }
                if (dcRightsMetadataValue != null) {
                    deleteItemMetadataValue(context, source, dcRightsMetadataValue);
                }
            }

            if (dcRightsMetadataValue != null && dcRightsMetadataValue.getValue()
                    .equals("Creative Commons Attribution 4.0 International Public License")) {
                setCCLicense(context, source);
            } else {
                removeCCLicense(context, source);
            }

        }
    }

    private void deleteItemMetadataValue(Context context, InProgressSubmission source, MetadataValue mv)
            throws SQLException {
        // Remove metadata value association before deletion
        List<MetadataValue> itemMetadata = source.getItem().getMetadata();
        itemMetadata.remove(mv);
        source.getItem().setMetadata(itemMetadata);
        // Delete the metadata value
        metadataValueService.delete(context, mv);
    }

    private void setCCLicense(Context context, InProgressSubmission source) {
        try {
            creativeCommonsService.setLicense(context, source.getItem(), new FileInputStream(CREATIVE_COMMONS_BY_LICENCE_FILE),
                    "text/plain");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private void removeCCLicense(Context context, InProgressSubmission source) {
        try {
            creativeCommonsService.removeLicense(context, source.getItem());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private List<String> getInputFieldsName(DCInputSet inputConfig, String configId) throws DCInputsReaderException {
        List<String> fieldsName = new ArrayList<String>();
        for (DCInput[] row : inputConfig.getFields()) {
            for (DCInput input : row) {
                if (input.isQualdropValue()) {
                    for (Object qualifier : input.getPairs()) {
                        fieldsName.add(input.getFieldName() + "." + (String) qualifier);
                    }
                } else if (StringUtils.equalsIgnoreCase(input.getInputType(), "group") ||
                        StringUtils.equalsIgnoreCase(input.getInputType(), "inline-group")) {
                    log.info("Called child form:" + configId + "-" +
                            Utils.standardize(input.getSchema(), input.getElement(), input.getQualifier(), "-"));
                    DCInputSet inputConfigChild = inputReader.getInputsByFormName(configId + "-" + Utils
                            .standardize(input.getSchema(), input.getElement(), input.getQualifier(), "-"));
                    fieldsName.addAll(getInputFieldsName(inputConfigChild, configId));
                } else {
                    fieldsName.add(input.getFieldName());
                }
            }
        }
        return fieldsName;
    }
}

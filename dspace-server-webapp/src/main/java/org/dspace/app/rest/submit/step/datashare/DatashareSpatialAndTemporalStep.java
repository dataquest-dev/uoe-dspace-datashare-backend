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
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
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
public class DatashareSpatialAndTemporalStep extends AbstractProcessingStep {

    private static final Logger log =
        org.apache.logging.log4j.LogManager.getLogger(DatashareSpatialAndTemporalStep.class);

    // Input reader for form configuration
    private DCInputsReader inputReader;
    // Configuration service
    private final ConfigurationService configurationService = DSpaceServicesFactory.getInstance()
            .getConfigurationService();

    private CreativeCommonsService creativeCommonsService = LicenseServiceFactory.getInstance()
            .getCreativeCommonsService();

    private ItemService itemService = ContentServiceFactory.getInstance().getItemService();

    public DatashareSpatialAndTemporalStep() throws DCInputsReaderException {
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
            populateTimePeriodFromTemporal(obj, data);
        } catch (DCInputsReaderException e) {
            log.error(e.getMessage(), e);
        }
        return data;
    }

    /**
     * If dc.coverage.temporal exists but dc.coverage.startDate / dc.coverage.endDate are absent,
     * decode the temporal value and populate the form data so that the Angular form shows the dates.
     */
    private void populateTimePeriodFromTemporal(InProgressSubmission obj, DataDescribe data) {
        boolean hasStartDate = data.getMetadata().containsKey("dc.coverage.startDate");
        boolean hasEndDate = data.getMetadata().containsKey("dc.coverage.endDate");

        if (hasStartDate && hasEndDate) {
            return;
        }

        List<MetadataValue> temporalValues = itemService.getMetadataByMetadataString(
                obj.getItem(), "dc.coverage.temporal");
        if (temporalValues == null || temporalValues.isEmpty()) {
            return;
        }

        String[] decoded = decodeTimePeriod(temporalValues.get(0).getValue());
        if (decoded == null) {
            return;
        }

        if (!hasStartDate && decoded[0] != null && !decoded[0].isEmpty()) {
            MetadataValueRest startDto = new MetadataValueRest();
            startDto.setValue(decoded[0]);
            List<MetadataValueRest> startList = new ArrayList<>();
            startList.add(startDto);
            data.getMetadata().put("dc.coverage.startDate", startList);
        }

        if (!hasEndDate && decoded[1] != null && !decoded[1].isEmpty()) {
            MetadataValueRest endDto = new MetadataValueRest();
            endDto.setValue(decoded[1]);
            List<MetadataValueRest> endList = new ArrayList<>();
            endList.add(endDto);
            data.getMetadata().put("dc.coverage.endDate", endList);
        }
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

        // Hydrate: if dc.coverage.temporal is stored but the individual date fields are absent,
        // decode temporal back into dc.coverage.startDate / dc.coverage.endDate so that
        // PATCH replace/remove operations can find the metadata values on the item.
        hydrateTimePeriodFields(context, source);

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
            List<MetadataValue> startDates = itemService.getMetadataByMetadataString(
                    source.getItem(), "dc.coverage.startDate");
            List<MetadataValue> endDates = itemService.getMetadataByMetadataString(
                    source.getItem(), "dc.coverage.endDate");

            if (!startDates.isEmpty() && !endDates.isEmpty()) {
                String encodedTimePeriod = encodeTimePeriod(
                        startDates.get(0).getValue(), endDates.get(0).getValue());
                log.info("encodedTimePeriod: " + encodedTimePeriod);

                // Use itemService to ensure the item's in-memory metadata list stays in sync
                itemService.clearMetadata(context, source.getItem(),
                        "dc", "coverage", "temporal", Item.ANY);
                itemService.addMetadata(context, source.getItem(),
                        "dc", "coverage", "temporal", null, encodedTimePeriod);

                itemService.clearMetadata(context, source.getItem(),
                        "dc", "coverage", "startDate", Item.ANY);
                itemService.clearMetadata(context, source.getItem(),
                        "dc", "coverage", "endDate", Item.ANY);
            } else {
                // Incomplete date pair — remove any stale temporal encoding
                itemService.clearMetadata(context, source.getItem(),
                        "dc", "coverage", "temporal", Item.ANY);
            }
        }
    }

    /**
     * If dc.coverage.temporal is stored but dc.coverage.startDate / dc.coverage.endDate are absent,
     * decode the temporal value and recreate the individual date fields on the item.
     * This allows subsequent PATCH replace/remove operations to find the metadata values.
     */
    private void hydrateTimePeriodFields(Context context, InProgressSubmission source) throws Exception {
        List<MetadataValue> startDates = itemService.getMetadataByMetadataString(
                source.getItem(), "dc.coverage.startDate");
        List<MetadataValue> endDates = itemService.getMetadataByMetadataString(
                source.getItem(), "dc.coverage.endDate");

        if (!startDates.isEmpty() && !endDates.isEmpty()) {
            return; // Both date fields already exist — nothing to hydrate
        }

        List<MetadataValue> temporalValues = itemService.getMetadataByMetadataString(
                source.getItem(), "dc.coverage.temporal");
        if (temporalValues.isEmpty()) {
            return; // No temporal to decode
        }

        String[] decoded = decodeTimePeriod(temporalValues.get(0).getValue());
        if (decoded == null) {
            return;
        }

        // Remove temporal, recreate individual date fields
        itemService.clearMetadata(context, source.getItem(), "dc", "coverage", "temporal", Item.ANY);

        if (decoded[0] != null && !decoded[0].isEmpty()) {
            itemService.addMetadata(context, source.getItem(),
                    "dc", "coverage", "startDate", null, decoded[0]);
        }
        if (decoded[1] != null && !decoded[1].isEmpty()) {
            itemService.addMetadata(context, source.getItem(),
                    "dc", "coverage", "endDate", null, decoded[1]);
        }
    }

    /**
     * Encode a start and end date into W3CDTF profile of ISO 8601.
     *
     * @param start Start date.
     * @param end   End date.
     * @return W3CDTF profile of ISO 8601.
     */
    static String encodeTimePeriod(String start, String end) {
        String ENCODING_SCHEME = "W3C-DTF";
        String startStr = "";
        String endString = "";

        if (start != null) {
            startStr = start;
        }

        if (end != null) {
            endString = end;
        }

        StringBuffer buf = new StringBuffer("start=");
        buf.append(startStr);
        buf.append("; end=");
        buf.append(endString);
        buf.append("; ");
        buf.append("scheme=");
        buf.append(ENCODING_SCHEME);

        return buf.toString();
    }

    /**
     * Decode a W3CDTF encoded time period string into start and end date components.
     *
     * @param temporal Encoded string, e.g. "start=2021; end=2025; scheme=W3C-DTF".
     * @return String array [startDate, endDate], or null if input is null/empty.
     */
    static String[] decodeTimePeriod(String temporal) {
        if (temporal == null || temporal.isEmpty()) {
            return null;
        }

        String startDate = null;
        String endDate = null;

        String[] tokens = temporal.split(";");
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.startsWith("start=")) {
                startDate = trimmed.substring("start=".length());
            } else if (trimmed.startsWith("end=")) {
                endDate = trimmed.substring("end=".length());
            }
        }

        return new String[]{startDate, endDate};
    }

    private void setCCLicense(Context context, InProgressSubmission source) {
        try {
            creativeCommonsService.setLicense(context, source.getItem(),
                    new FileInputStream(CREATIVE_COMMONS_BY_LICENCE_FILE),
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

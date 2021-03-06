package org.recap.etl;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.marc4j.marc.Leader;
import org.marc4j.marc.Record;
import org.recap.ReCAPConstants;
import org.recap.marc.BibMarcRecord;
import org.recap.marc.HoldingsMarcRecord;
import org.recap.marc.ItemMarcRecord;
import org.recap.model.*;
import org.recap.util.DBReportUtil;
import org.recap.util.MarcUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Created by chenchulakshmig on 17/10/16.
 */
@Component
public class MarcToBibEntityConverter {

    @Value("${server.protocol}")
    String serverProtocol;

    @Value("${scsb.persistence.url}")
    String scsbPersistenceUrl;

    private Map itemStatusMap;
    private Map collectionGroupMap;
    private Map institutionEntityMap;
    private RestTemplate restTemplate;

    @Autowired
    private MarcUtil marcUtil;

    @Autowired
    private DBReportUtil dbReportUtil;

    public Map convert(Record record, String institutionName) {
        Map<String, Object> map = new HashMap<>();
        boolean processBib = false;

        List<HoldingsEntity> holdingsEntities = new ArrayList<>();
        List<ItemEntity> itemEntities = new ArrayList<>();
        List<ReportEntity> reportEntities = new ArrayList<>();

        getDbReportUtil().setInstitutionEntitiesMap(getInstitutionEntityMap());
        getDbReportUtil().setCollectionGroupMap(getCollectionGroupMap());

        BibMarcRecord bibMarcRecord = marcUtil.buildBibMarcRecord(record);
        Record bibRecord = bibMarcRecord.getBibRecord();
        Integer owningInstitutionId = (Integer) getInstitutionEntityMap().get(institutionName);
        Map<String, Object> bibMap = processAndValidateBibliographicEntity(bibRecord, owningInstitutionId, institutionName);
        BibliographicEntity bibliographicEntity = (BibliographicEntity) bibMap.get("bibliographicEntity");
        ReportEntity bibReportEntity = (ReportEntity) bibMap.get("bibReportEntity");
        if (bibReportEntity != null) {
            reportEntities.add(bibReportEntity);
        } else {
            processBib = true;
        }

        List<HoldingsMarcRecord> holdingsMarcRecords = bibMarcRecord.getHoldingsMarcRecords();
        if (CollectionUtils.isNotEmpty(holdingsMarcRecords)) {
            for (HoldingsMarcRecord holdingsMarcRecord : holdingsMarcRecords) {
                boolean processHoldings = false;
                Record holdingsRecord = holdingsMarcRecord.getHoldingsRecord();
                Map<String, Object> holdingsMap = processAndValidateHoldingsEntity(bibliographicEntity, institutionName, holdingsRecord, bibRecord);
                HoldingsEntity holdingsEntity = (HoldingsEntity) holdingsMap.get("holdingsEntity");
                ReportEntity holdingsReportEntity = (ReportEntity) holdingsMap.get("holdingsReportEntity");
                if (holdingsReportEntity != null) {
                    reportEntities.add(holdingsReportEntity);
                } else {
                    processHoldings = true;
                    holdingsEntities.add(holdingsEntity);
                }
                String holdingsCallNumber = marcUtil.getDataFieldValue(holdingsRecord, "852", 'h');
                Character holdingsCallNumberType = marcUtil.getInd1(holdingsRecord, "852", 'h');

                List<ItemMarcRecord> itemMarcRecordList = holdingsMarcRecord.getItemMarcRecordList();
                if (CollectionUtils.isNotEmpty(itemMarcRecordList)) {
                    for (ItemMarcRecord itemMarcRecord : itemMarcRecordList) {
                        Record itemRecord = itemMarcRecord.getItemRecord();
                        Map<String, Object> itemMap = processAndValidateItemEntity(bibliographicEntity, holdingsEntity, owningInstitutionId, holdingsCallNumber, holdingsCallNumberType, itemRecord, institutionName, bibRecord);
                        ItemEntity itemEntity = (ItemEntity) itemMap.get("itemEntity");
                        ReportEntity itemReportEntity = (ReportEntity) itemMap.get("itemReportEntity");
                        if (itemReportEntity != null) {
                            reportEntities.add(itemReportEntity);
                        } else if (processHoldings) {
                            if (holdingsEntity.getItemEntities() == null) {
                                holdingsEntity.setItemEntities(new ArrayList<>());
                            }
                            holdingsEntity.getItemEntities().add(itemEntity);
                            itemEntities.add(itemEntity);
                        }
                    }
                }

            }
            bibliographicEntity.setHoldingsEntities(holdingsEntities);
            bibliographicEntity.setItemEntities(itemEntities);
        }

        if (CollectionUtils.isNotEmpty(reportEntities)) {
            map.put("reportEntities", reportEntities);
        }
        if (processBib) {
            map.put("bibliographicEntity", bibliographicEntity);
        }
        return map;
    }

    private Map<String, Object> processAndValidateBibliographicEntity(Record bibRecord, Integer owningInstitutionId, String institutionName) {
        Map<String, Object> map = new HashMap<>();
        BibliographicEntity bibliographicEntity = new BibliographicEntity();
        StringBuffer errorMessage = new StringBuffer();

        String owningInstitutionBibId = marcUtil.getControlFieldValue(bibRecord, "001");
        if (StringUtils.isNotBlank(owningInstitutionBibId)) {
            bibliographicEntity.setOwningInstitutionBibId(owningInstitutionBibId);
        } else {
            errorMessage.append("Owning Institution Bib Id cannot be null");
        }
        if (owningInstitutionId != null) {
            bibliographicEntity.setOwningInstitutionId(owningInstitutionId);
        } else {
            errorMessage.append("\n");
            errorMessage.append("Owning Institution Id cannot be null");
        }
        bibliographicEntity.setCreatedDate(new Date());
        bibliographicEntity.setCreatedBy("accession");
        bibliographicEntity.setLastUpdatedDate(new Date());
        bibliographicEntity.setLastUpdatedBy("accession");

        String bibContent = marcUtil.writeMarcXml(bibRecord);
        if (StringUtils.isNotBlank(bibContent)) {
            bibliographicEntity.setContent(bibContent.getBytes());
        } else {
            errorMessage.append("\n");
            errorMessage.append("Bib Content cannot be empty");
        }

        boolean subFieldExistsFor245 = marcUtil.isSubFieldExists(bibRecord, "245");
        if (!subFieldExistsFor245) {
            errorMessage.append("\n");
            errorMessage.append("Atleast one subfield should be there for 245 tag");
        }
        Leader leader = bibRecord.getLeader();
        if (leader != null) {
            String leaderValue = bibRecord.getLeader().toString();
            if (!(StringUtils.isNotBlank(leaderValue) && leaderValue.length() == 24)) {
                errorMessage.append("\n");
                errorMessage.append("Leader Field value should be 24 characters");
            }
        }
        List<ReportDataEntity> reportDataEntities = null;
        if (errorMessage.toString().length() > 1) {
            reportDataEntities = getDbReportUtil().generateBibFailureReportEntity(bibliographicEntity, bibRecord);
            ReportDataEntity errorReportDataEntity = new ReportDataEntity();
            errorReportDataEntity.setHeaderName(ReCAPConstants.ERROR_DESCRIPTION);
            errorReportDataEntity.setHeaderValue(errorMessage.toString());
            reportDataEntities.add(errorReportDataEntity);
        }
        if (!CollectionUtils.isEmpty(reportDataEntities)) {
            ReportEntity reportEntity = new ReportEntity();
            reportEntity.setFileName("Accession_Failure_Report");
            reportEntity.setInstitutionName(institutionName);
            reportEntity.setType(org.recap.ReCAPConstants.FAILURE);
            reportEntity.setCreatedDate(new Date());
            reportEntity.addAll(reportDataEntities);
            map.put("bibReportEntity", reportEntity);
        }
        map.put("bibliographicEntity", bibliographicEntity);
        return map;
    }

    private Map<String, Object> processAndValidateHoldingsEntity(BibliographicEntity bibliographicEntity, String institutionName, Record holdingsRecord, Record bibRecord) {
        StringBuffer errorMessage = new StringBuffer();
        Map<String, Object> map = new HashMap<>();
        HoldingsEntity holdingsEntity = new HoldingsEntity();

        String holdingsContent = new MarcUtil().writeMarcXml(holdingsRecord);
        if (StringUtils.isNotBlank(holdingsContent)) {
            holdingsEntity.setContent(holdingsContent.getBytes());
        } else {
            errorMessage.append("Holdings Content cannot be empty");
        }
        holdingsEntity.setCreatedDate(new Date());
        holdingsEntity.setCreatedBy("accession");
        holdingsEntity.setLastUpdatedDate(new Date());
        holdingsEntity.setLastUpdatedBy("accession");
        Integer owningInstitutionId = bibliographicEntity.getOwningInstitutionId();
        holdingsEntity.setOwningInstitutionId(owningInstitutionId);
        String owningInstitutionHoldingsId = marcUtil.getDataFieldValue(holdingsRecord, "852", '0');
        if (StringUtils.isBlank(owningInstitutionHoldingsId)) {
            owningInstitutionHoldingsId = UUID.randomUUID().toString();
        } else if (owningInstitutionHoldingsId.length() > 100) {
            owningInstitutionHoldingsId = UUID.randomUUID().toString();
        }
        holdingsEntity.setOwningInstitutionHoldingsId(owningInstitutionHoldingsId);
        List<ReportDataEntity> reportDataEntities = null;
        if (errorMessage.toString().length() > 1) {
            reportDataEntities = getDbReportUtil().generateBibHoldingsFailureReportEntity(bibliographicEntity, holdingsEntity, institutionName, bibRecord);
            ReportDataEntity errorReportDataEntity = new ReportDataEntity();
            errorReportDataEntity.setHeaderName(ReCAPConstants.ERROR_DESCRIPTION);
            errorReportDataEntity.setHeaderValue(errorMessage.toString());
            reportDataEntities.add(errorReportDataEntity);
        }

        if (!org.springframework.util.CollectionUtils.isEmpty(reportDataEntities)) {
            ReportEntity reportEntity = new ReportEntity();
            reportEntity.setFileName("Accession_Failure_Report");
            reportEntity.setInstitutionName(institutionName);
            reportEntity.setType(org.recap.ReCAPConstants.FAILURE);
            reportEntity.setCreatedDate(new Date());
            reportEntity.addAll(reportDataEntities);
            map.put("holdingsReportEntity", reportEntity);
        }
        map.put("holdingsEntity", holdingsEntity);
        return map;
    }

    private Map<String, Object> processAndValidateItemEntity(BibliographicEntity bibliographicEntity, HoldingsEntity holdingsEntity, Integer owningInstitutionId, String holdingsCallNumber, Character holdingsCallNumberType, Record itemRecord, String institutionName, Record bibRecord) {
        StringBuffer errorMessage = new StringBuffer();
        Map<String, Object> map = new HashMap<>();
        ItemEntity itemEntity = new ItemEntity();

        String itemBarcode = marcUtil.getDataFieldValue(itemRecord, "876", 'p');
        if (StringUtils.isNotBlank(itemBarcode)) {
            itemEntity.setBarcode(itemBarcode);
        } else {
            errorMessage.append("Item Barcode cannot be null");
        }
        String customerCode = marcUtil.getDataFieldValue(itemRecord, "876", 'z');
        if (StringUtils.isNotBlank(customerCode)) {
            itemEntity.setCustomerCode(customerCode);
        } else {
            errorMessage.append("\n");
            errorMessage.append("Customer Code cannot be null");
        }
        itemEntity.setCallNumber(holdingsCallNumber);
        itemEntity.setCallNumberType(String.valueOf(holdingsCallNumberType));
        itemEntity.setItemAvailabilityStatusId((Integer) getItemStatusMap().get("Available"));//TODO need to change
        String copyNumber = marcUtil.getDataFieldValue(itemRecord, "876", 't');
        if (StringUtils.isNoneBlank(copyNumber) && org.apache.commons.lang3.math.NumberUtils.isNumber(copyNumber)) {
            itemEntity.setCopyNumber(Integer.valueOf(copyNumber));
        }
        if (owningInstitutionId != null) {
            itemEntity.setOwningInstitutionId(owningInstitutionId);
        } else {
            errorMessage.append("\n");
            errorMessage.append("Owning Institution Id cannot be null");
        }
        String collectionGroupCode = marcUtil.getDataFieldValue(itemRecord, "876", 'x');
        if (StringUtils.isNotBlank(collectionGroupCode) && getCollectionGroupMap().containsKey(collectionGroupCode)) {
            itemEntity.setCollectionGroupId((Integer) getCollectionGroupMap().get(collectionGroupCode));
        } else {
            itemEntity.setCollectionGroupId((Integer) getCollectionGroupMap().get("Open"));
        }
        itemEntity.setCreatedDate(new Date());
        itemEntity.setCreatedBy("accession");
        itemEntity.setLastUpdatedDate(new Date());
        itemEntity.setLastUpdatedBy("accession");

        String useRestrictions = marcUtil.getDataFieldValue(itemRecord, "876", 'h');
        if (StringUtils.isNotBlank(useRestrictions) && (useRestrictions.equalsIgnoreCase("In Library Use") || useRestrictions.equalsIgnoreCase("Supervised Use"))) {
            itemEntity.setUseRestrictions(useRestrictions);
        }

        itemEntity.setVolumePartYear(marcUtil.getDataFieldValue(itemRecord, "876", '3'));
        String owningInstitutionItemId = marcUtil.getDataFieldValue(itemRecord, "876", 'a');
        if (StringUtils.isNotBlank(owningInstitutionItemId)) {
            itemEntity.setOwningInstitutionItemId(owningInstitutionItemId);
        } else {
            errorMessage.append("\n");
            errorMessage.append("Item Owning Institution Id cannot be null");
        }

        List<ReportDataEntity> reportDataEntities = null;
        if (errorMessage.toString().length() > 1) {
            reportDataEntities = getDbReportUtil().generateBibHoldingsAndItemsFailureReportEntities(bibliographicEntity, holdingsEntity, itemEntity, institutionName, bibRecord);
            ReportDataEntity errorReportDataEntity = new ReportDataEntity();
            errorReportDataEntity.setHeaderName(ReCAPConstants.ERROR_DESCRIPTION);
            errorReportDataEntity.setHeaderValue(errorMessage.toString());
            reportDataEntities.add(errorReportDataEntity);
        }
        if (!org.springframework.util.CollectionUtils.isEmpty(reportDataEntities)) {
            ReportEntity reportEntity = new ReportEntity();
            reportEntity.setFileName("Accession_Failure_Report");
            reportEntity.setInstitutionName(institutionName);
            reportEntity.setType(org.recap.ReCAPConstants.FAILURE);
            reportEntity.setCreatedDate(new Date());
            reportEntity.addAll(reportDataEntities);
            map.put("itemReportEntity", reportEntity);
        }
        map.put("itemEntity", itemEntity);
        return map;
    }

    public Map getItemStatusMap() {
        if (null == itemStatusMap) {
            itemStatusMap = new HashMap();
            try {
                String itemStatusJsonResponse = getRestTemplate().getForObject(serverProtocol + scsbPersistenceUrl + "itemStatus", String.class);
                JSONObject jsonResponse = new JSONObject(itemStatusJsonResponse).getJSONObject("_embedded");
                JSONArray itemStatusEntities = jsonResponse.getJSONArray("itemStatus");
                for (int i = 0; i < itemStatusEntities.length(); i++) {
                    JSONObject itemStatusEntity = itemStatusEntities.getJSONObject(i);
                    itemStatusMap.put(itemStatusEntity.getString("statusCode"), itemStatusEntity.getInt("itemStatusId"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return itemStatusMap;
    }

    public Map getCollectionGroupMap() {
        if (null == collectionGroupMap) {
            collectionGroupMap = new HashMap();
            try {
                String collectionGroupJsonResponse = getRestTemplate().getForObject(serverProtocol + scsbPersistenceUrl + "collectionGroup", String.class);
                JSONObject jsonResponse = new JSONObject(collectionGroupJsonResponse).getJSONObject("_embedded");
                JSONArray collectionGroupEntities = jsonResponse.getJSONArray("collectionGroup");
                for (int i = 0; i < collectionGroupEntities.length(); i++) {
                    JSONObject collectionGroupEntity = collectionGroupEntities.getJSONObject(i);
                    collectionGroupMap.put(collectionGroupEntity.getString("collectionGroupCode"), collectionGroupEntity.getInt("collectionGroupId"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return collectionGroupMap;
    }

    public Map getInstitutionEntityMap() {
        if (null == institutionEntityMap) {
            institutionEntityMap = new HashMap();
            try {
                String institutionJsonResponse = getRestTemplate().getForObject(serverProtocol + scsbPersistenceUrl + "institution", String.class);
                JSONObject jsonResponse = new JSONObject(institutionJsonResponse).getJSONObject("_embedded");
                JSONArray institutionEntities = jsonResponse.getJSONArray("institution");
                for (int i = 0; i < institutionEntities.length(); i++) {
                    JSONObject institutionEntity = institutionEntities.getJSONObject(i);
                    institutionEntityMap.put(institutionEntity.getString("institutionCode"), institutionEntity.getInt("institutionId"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return institutionEntityMap;
    }

    public RestTemplate getRestTemplate() {
        if (null == restTemplate) {
            restTemplate = new RestTemplate();
        }
        return restTemplate;
    }

    public DBReportUtil getDbReportUtil() {
        return dbReportUtil;
    }

    public void setDbReportUtil(DBReportUtil dbReportUtil) {
        this.dbReportUtil = dbReportUtil;
    }
}

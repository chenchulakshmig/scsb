package org.recap.controller.swagger;

import io.swagger.annotations.*;
import org.apache.commons.lang3.StringUtils;
import org.recap.ReCAPConstants;
import org.recap.model.AccessionRequest;
import org.recap.model.DeAccessionDBResponseEntity;
import org.recap.model.DeAccessionRequest;
import org.recap.util.AccessionService;
import org.recap.util.DeAccessionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Created by chenchulakshmig on 6/10/16.
 */
@RestController
@RequestMapping("/sharedCollection")
@Api(value = "sharedCollection", description = "Shared Collection", position = 1)
public class SharedCollectionRestController {

    @Value("${server.protocol}")
    String serverProtocol;

    @Value("${scsb.persistence.url}")
    String scsbPersistenceUrl;

    @Autowired
    DeAccessionUtil deAccessionUtil;

    @Autowired
    AccessionService accessionService;

    @RequestMapping(value = "/itemAvailabilityStatus", method = RequestMethod.GET)
    @ApiOperation(value = "itemAvailabilityStatus",
            notes = "Item Availability Status", nickname = "itemAvailabilityStatus", position = 0)
    @ApiResponses(value = {@ApiResponse(code = 200, message = "OK")})
    @ResponseBody
    public ResponseEntity itemAvailabilityStatus(@ApiParam(value = "Item Barcode", required = true, name = "itemBarcode") @RequestParam String itemBarcode) {
        RestTemplate restTemplate = new RestTemplate();
        String itemStatus = null;
        try {
            itemStatus = restTemplate.getForObject(serverProtocol + scsbPersistenceUrl + "item/getItemStatusByBarcodeAndIsDeletedFalse?barcode=" + itemBarcode, String.class);
        } catch (Exception exception) {
            ResponseEntity responseEntity = new ResponseEntity("Scsb Persistence Service is Unavailable.", getHttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE);
            return responseEntity;
        }
        if (StringUtils.isEmpty(itemStatus)) {
            ResponseEntity responseEntity = new ResponseEntity(ReCAPConstants.ITEM_BARCDE_DOESNOT_EXIST, getHttpHeaders(), HttpStatus.OK);
            return responseEntity;
        } else {
            ResponseEntity responseEntity = new ResponseEntity(itemStatus, getHttpHeaders(), HttpStatus.OK);
            return responseEntity;
        }
    }

    @RequestMapping(value = "/deAccession", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation(value = "deAccession",
            notes = "De Accession", nickname = "deaccession", position = 0)
    @ApiResponses(value = {@ApiResponse(code = 200, message = "OK")})
    @ResponseBody
    public ResponseEntity deAccession(@ApiParam(value = "Item Barcodes with ',' separated", required = true, name = "itemBarcodes") @RequestBody DeAccessionRequest deAccessionRequest) {
        List<DeAccessionDBResponseEntity> deAccessionDBResponseEntities = deAccessionUtil.deAccessionItemsInDB(deAccessionRequest.getItemBarcodes());
        deAccessionUtil.processAndSave(deAccessionDBResponseEntities);
        if (!CollectionUtils.isEmpty(deAccessionDBResponseEntities)) {
            Map<String, String> resultMap = new HashMap<>();
            List<Integer> bibIds = new ArrayList<>();
            List<Integer> holdingsIds = new ArrayList<>();
            List<Integer> itemIds = new ArrayList<>();
            Map<String, Integer> ownInstAndItemIdMap = new HashMap<>();
            for (DeAccessionDBResponseEntity deAccessionDBResponseEntity : deAccessionDBResponseEntities) {
                if (deAccessionDBResponseEntity.getStatus().equalsIgnoreCase(ReCAPConstants.FAILURE)) {
                    resultMap.put(deAccessionDBResponseEntity.getBarcode(), deAccessionDBResponseEntity.getStatus() + " - " + deAccessionDBResponseEntity.getReasonForFailure());
                } else if (deAccessionDBResponseEntity.getStatus().equalsIgnoreCase(ReCAPConstants.SUCCESS)) {
                    resultMap.put(deAccessionDBResponseEntity.getBarcode(), deAccessionDBResponseEntity.getStatus());
                    bibIds.addAll(deAccessionDBResponseEntity.getBibliographicIds());
                    holdingsIds.addAll(deAccessionDBResponseEntity.getHoldingIds());
                    itemIds.add(deAccessionDBResponseEntity.getItemId());
                    ownInstAndItemIdMap.put(deAccessionDBResponseEntity.getInstitutionCode(), deAccessionDBResponseEntity.getItemId());
                }
            }
            deAccessionUtil.checkAndCancelHoldsIfExists(ownInstAndItemIdMap);
            deAccessionUtil.deAccessionItemsInSolr(bibIds, holdingsIds, itemIds);
            ResponseEntity responseEntity = new ResponseEntity(resultMap, getHttpHeaders(), HttpStatus.OK);
            return responseEntity;
        }
        return null;
    }

    @RequestMapping(value = "/accession", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiOperation(value = "accession",
            notes = "Accession", nickname = "accession", position = 0)
    @ApiResponses(value = {@ApiResponse(code = 200, message = "OK")})
    @ResponseBody
    public ResponseEntity accession(@ApiParam(value = "Item Barcode and Customer Code", required = true, name = "Item Barcode And Customer Code") @RequestBody AccessionRequest accessionRequest) {
        String owningInstitution = accessionService.getOwningInstitution(accessionRequest.getCustomerCode());
        if (StringUtils.isBlank(owningInstitution)) {
            ResponseEntity responseEntity = new ResponseEntity(ReCAPConstants.CUSTOMER_CODE_DOESNOT_EXIST, getHttpHeaders(), HttpStatus.OK);
            return responseEntity;
        } else {
            String response = accessionService.processRequest(accessionRequest.getItemBarcode(), owningInstitution);
            ResponseEntity responseEntity = new ResponseEntity(response, getHttpHeaders(), HttpStatus.OK);
            return responseEntity;
        }
    }

    private HttpHeaders getHttpHeaders() {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add(ReCAPConstants.RESPONSE_DATE, new Date().toString());
        return responseHeaders;
    }

}

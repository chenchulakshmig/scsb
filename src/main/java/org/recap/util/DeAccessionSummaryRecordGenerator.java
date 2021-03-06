package org.recap.util;

import org.apache.commons.beanutils.PropertyUtilsBean;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.recap.csv.DeAccessionSummaryRecord;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Created by chenchulakshmig on 13/10/16.
 */
public class DeAccessionSummaryRecordGenerator {

    public DeAccessionSummaryRecord prepareDeAccessionSummaryReportRecord(JSONObject reportEntity) {

        DeAccessionSummaryRecord deAccessionSummaryRecord = new DeAccessionSummaryRecord();
        try {
            JSONArray reportDataEntities = reportEntity.getJSONArray("reportDataEntities");
            for (int i = 0; i < reportDataEntities.length(); i++) {
                JSONObject reportDataEntity = reportDataEntities.getJSONObject(i);
                String headerName = reportDataEntity.getString("headerName");
                String headerValue = reportDataEntity.getString("headerValue");
                Method setterMethod = getSetterMethod(headerName);
                if (null != setterMethod) {
                    try {
                        setterMethod.invoke(deAccessionSummaryRecord, headerValue);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    } catch (InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return deAccessionSummaryRecord;
    }

    public Method getSetterMethod(String propertyName) {
        PropertyUtilsBean propertyUtilsBean = new PropertyUtilsBean();
        try {
            Method writeMethod = propertyUtilsBean.getWriteMethod(new PropertyDescriptor(propertyName, DeAccessionSummaryRecord.class));
            return writeMethod;
        } catch (IntrospectionException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Method getGetterMethod(String propertyName) {
        PropertyUtilsBean propertyUtilsBean = new PropertyUtilsBean();
        try {
            Method writeMethod = propertyUtilsBean.getReadMethod(new PropertyDescriptor(propertyName, DeAccessionSummaryRecord.class));
            return writeMethod;
        } catch (IntrospectionException e) {
            e.printStackTrace();
        }
        return null;
    }
}

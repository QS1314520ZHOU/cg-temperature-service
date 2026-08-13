package com.digixmed.cloud.icu.service;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.service.common.BaseService;
import com.digixmed.cloud.icu.service.common.HandleService;
import com.digixmed.cloud.icu.service.common.MyConfig;
import com.digixmed.cloud.icu.util.DataUtils;
import com.digixmed.cloud.icu.util.DateUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UrinateServiceImpl
        extends HandleService implements BaseService {
    /*  26 */   private static final Logger log = LoggerFactory.getLogger(UrinateServiceImpl.class);

    @Value("${digixmed.timePointasd}")
    public Integer timePointasd;


    public IntermediateTable handle(Document bedside) {
        /*  33 */
        String bedSideId = ((ObjectId) getValueFromDocByKey(bedside, "_id", ObjectId.class)).toHexString();

        /*  35 */
        String pid = (String) getValueFromDocByKey(bedside, "pid", String.class);
        /*  36 */
        String code = (String) getValueFromDocByKey(bedside, "code", String.class);
        /*  37 */
        Date startTime = (Date) getValueFromDocByKey(bedside, "time", Date.class);
        /*  38 */
        if (startTime == null) {
            /*  39 */
            return null;
        }
        /*  41 */
        Date endTime = DateUtils.getTomorrow(startTime);
        /*  42 */
        List<Document> documents = selectBedSidesRDXWithCode(startTime, endTime, pid, code);

        /*  44 */
        Document crlDoc = CRStatistics(documents);
        /*  45 */
        if (crlDoc == null) {
            /*  46 */
            return null;
        }
        /*  48 */
        IntermediateTable intermediateTable = new IntermediateTable();
        /*  49 */
        Document patient = queryPatientByPid(pid);
        /*  50 */
        String sideName = "";

        try {
            /*  53 */
            intermediateTable.setTimePoint(MyConfig.STARTTIME ? startTime : endTime);
            /*  54 */
            intermediateTable.setCreateTime((Date) getValueFromDocByKey(crlDoc, "editTime", Date.class));
            /*  55 */
            intermediateTable.setLastEditTime((Date) getValueFromDocByKey(crlDoc, "editTime", Date.class));
            /*  56 */
            intermediateTable.setSignValue((String) getValueFromDocByKey(crlDoc, "strVal", String.class));
            /*  57 */
            sideName = DataUtils.getSignNameByBedSideCode(code);
            /*  58 */
            intermediateTable.setSignName(sideName);
            /*  59 */
            intermediateTable.setSignCode(code);
            /*  60 */
            intermediateTable.setSignUnit(DataUtils.getUnitByCode(code));
            /*  61 */
            Boolean validBool = (Boolean) getValueFromDocByKey(crlDoc, "valid", Boolean.class);
            intermediateTable.setIsValid(validBool != null && validBool ? 1 : 0);
            /*  62 */
            intermediateTable.setMrn((String) getValueFromDocByKey(patient, "hisPid", String.class));
            /*  63 */
            intermediateTable.setZycs((String) getValueFromDocByKey(patient, "hospitalTime", String.class));
            /*  64 */
            intermediateTable.setPatientName((String) getValueFromDocByKey(patient, "name", String.class));
            /*  65 */
            intermediateTable.setPatientId((String) getValueFromDocByKey(patient, "mrn", String.class));
            /*  66 */
            intermediateTable.setIsFirst(Integer.valueOf(0));
            /*  67 */
            intermediateTable.setIsUpload(Integer.valueOf(0));
            /*  68 */
            String edituser = getTheAccountIdInTime(pid, endTime);
            /*  69 */
            intermediateTable.setAuthorName(queryDoctorById(edituser, Boolean.valueOf(true)));
            /*  70 */
            intermediateTable.setAuthorId(queryDoctorById(edituser, Boolean.valueOf(false)));
            /*  71 */
            intermediateTable.setBedSideId(bedSideId);
            /*  72 */
            intermediateTable.setPid((String) getValueFromDocByKey(crlDoc, "pid", String.class));
            /*  73 */
            intermediateTable.setChlidList((List) getValueFromDocByKey(crlDoc, "countRecords", List.class));

            /*  75 */
            intermediateTable = special(intermediateTable, pid, bedside);
            /*  76 */
        } catch (Exception e) {
            /*  77 */
            log.error("bedsideId为{}的体征：{} 报错，对应的记录时间为：{},原因:{}", new Object[]{sideName, endTime, e.toString()});
        }
        /*  79 */
        Date icuAdmissionTime = (Date) getValueFromDocByKey(patient, "icuAdmissionTime", Date.class);
        /*  80 */
        Date addmitTime = (icuAdmissionTime != null) ? DateUtils.formatTimePoint(icuAdmissionTime, this.timePointasd.intValue()) : null;
        /*  81 */
        if (addmitTime != null && intermediateTable.getTimePoint().before(addmitTime)) {
            /*  82 */
            return null;
        }
        /*  84 */
        return intermediateTable;
    }


    public IntermediateTable special(IntermediateTable table, String pid, Document bedside) {
        /*  95 */
        return table;
    }


    private Document CRStatistics(List<Document> docs) {
        /* 105 */
        Document resultDoc = new Document();
        /* 106 */
        if (docs.size() != 0) {
            /* 107 */
            List<Document> bs = new ArrayList<>();
            /* 108 */
            Double ruliangCount = Double.valueOf(0.0D);
            /* 109 */
            Document resDoc = docs.get(0);

            /* 111 */
            for (Document doc : docs) {
                try {
                    /* 113 */
                    String strVal = doc.getString("strVal");
                    /* 114 */
                    if (strVal != null && !strVal.isEmpty()) {
                        /* 115 */
                        Double value = Convert.toDouble(strVal, Double.valueOf(0.0D));

                        /* 117 */
                        value = DataUtils.roundDouble(value);
                        /* 118 */
                        ruliangCount = Double.valueOf(ruliangCount.doubleValue() + value.doubleValue());

                        /* 120 */
                        Date time = doc.getDate("time");
                        /* 121 */
                        String bedsideId = doc.getObjectId("_id").toString();
                        /* 122 */
                        Document b = new Document();
                        /* 123 */
                        b.put("strVal", strVal);
                        /* 124 */
                        b.put("time", DateUtil.format(time, "yyyy-MM-dd HH:mm:ss"));
                        /* 125 */
                        b.put("bedsideId", bedsideId);
                        /* 126 */
                        if (b != null) {
                            /* 127 */
                            bs.add(b);
                        }
                    }
                    /* 130 */
                } catch (Exception e) {
                    /* 131 */
                    log.error("CRStatistics,原因:" + e.toString());
                }
            }
            /* 134 */
            if (resDoc != null && !resDoc.isEmpty()) {
















                /* 151 */
                String strVal = String.valueOf(Math.round(ruliangCount.doubleValue()));




                /* 156 */
                resDoc.put("strVal", strVal);
                /* 157 */
                resDoc.put("countRecords", bs);
                /* 158 */
                resultDoc = resDoc;
            } else {
                /* 160 */
                resultDoc.put("strVal", "0");
                /* 161 */
                resultDoc.put("countRecords", new ArrayList());
            }
        } else {
            /* 164 */
            resultDoc.put("strVal", "0");
            /* 165 */
            resultDoc.put("countRecords", new ArrayList());
        }
        /* 167 */
        return resultDoc;
    }
}


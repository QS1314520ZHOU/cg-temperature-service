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
public class FaecesServiceImpl
        extends HandleService implements BaseService {
    /*  26 */   private static final Logger log = LoggerFactory.getLogger(FaecesServiceImpl.class);

    @Value("${digixmed.timePointasd}")
    public Integer timePointasd;


    public IntermediateTable handle(Document bedside) {
        /*  33 */
        String bedSideId = ((ObjectId) getValueFromDocByKey(bedside, "_id", ObjectId.class)).toHexString();
        /*  34 */
        String pid = (String) getValueFromDocByKey(bedside, "pid", String.class);
        /*  35 */
        String code = (String) getValueFromDocByKey(bedside, "code", String.class);
        /*  36 */
        Date startTime = (Date) getValueFromDocByKey(bedside, "time", Date.class);
        /*  37 */
        Date endTime = DateUtils.getTomorrow(startTime);
        /*  38 */
        List<Document> documents = selectBedSidesRDXWithCode(startTime, endTime, pid, MyConfig.DBCODES);
        /*  39 */
        Document crlDoc = CRStatistics(documents);
        /*  40 */
        if (crlDoc == null) {
            /*  41 */
            return null;
        }
        /*  43 */
        IntermediateTable intermediateTable = new IntermediateTable();
        /*  44 */
        Document patient = queryPatientByPid(pid);
        /*  45 */
        String sideName = "";
        try {
            /*  47 */
            intermediateTable.setTimePoint(MyConfig.STARTTIME ? startTime : endTime);
            /*  48 */
            intermediateTable.setCreateTime((Date) getValueFromDocByKey(crlDoc, "editTime", Date.class));
            /*  49 */
            intermediateTable.setLastEditTime((Date) getValueFromDocByKey(crlDoc, "editTime", Date.class));
            /*  50 */
            intermediateTable.setSignValue((String) getValueFromDocByKey(crlDoc, "strVal", String.class));

            /*  52 */
            sideName = DataUtils.getSignNameByBedSideCode(code);
            /*  53 */
            intermediateTable.setSignName(sideName);
            /*  54 */
            intermediateTable.setSignCode(code);
            /*  55 */
            intermediateTable.setSignUnit(DataUtils.getUnitByCode(code));
            /*  56 */
            Boolean validBool = (Boolean) getValueFromDocByKey(crlDoc, "valid", Boolean.class);
            intermediateTable.setIsValid(validBool != null && validBool ? 1 : 0);
            /*  57 */
            intermediateTable.setMrn((String) getValueFromDocByKey(patient, "mrn", String.class));
            /*  58 */
            intermediateTable.setZycs((String) getValueFromDocByKey(patient, "hospitalTime", String.class));
            /*  59 */
            intermediateTable.setPatientName((String) getValueFromDocByKey(patient, "name", String.class));
            /*  60 */
            intermediateTable.setPatientId((String) getValueFromDocByKey(patient, "hisPid", String.class));
            /*  61 */
            intermediateTable.setIsFirst(Integer.valueOf(0));
            /*  62 */
            intermediateTable.setIsUpload(Integer.valueOf(0));
            /*  63 */
            String edituser = getTheAccountIdInTime(pid, endTime);
            /*  64 */
            intermediateTable.setAuthorName(queryDoctorById(edituser, Boolean.valueOf(true)));
            /*  65 */
            intermediateTable.setAuthorId(queryDoctorById(edituser, Boolean.valueOf(false)));
            /*  66 */
            intermediateTable.setBedSideId(bedSideId);
            /*  67 */
            intermediateTable.setPid((String) getValueFromDocByKey(crlDoc, "pid", String.class));
            /*  68 */
            intermediateTable.setChlidList((List) getValueFromDocByKey(crlDoc, "countRecords", List.class));

            /*  70 */
            intermediateTable = special(intermediateTable, pid, bedside);
            /*  71 */
        } catch (Exception e) {
            /*  72 */
            log.error("bedsideId为{}的体征：{} 报错，对应的记录时间为：{},原因:{}", new Object[]{sideName, endTime, e.toString()});
        }
        /*  74 */
        Date icuAdmissionTime = (Date) getValueFromDocByKey(patient, "icuAdmissionTime", Date.class);
        /*  75 */
        Date addmitTime = (icuAdmissionTime != null) ? DateUtils.formatTimePoint(icuAdmissionTime, this.timePointasd.intValue()) : null;
        /*  76 */
        if (addmitTime != null && intermediateTable.getTimePoint().before(addmitTime)) {
            /*  77 */
            return null;
        }
        /*  79 */
        return intermediateTable;
    }


    public IntermediateTable special(IntermediateTable table, String pid, Document bedside) {
        /*  85 */
        return table;
    }


    private Document CRStatistics(List<Document> docs) {
        /*  95 */
        Document resultDoc = new Document();
        /*  96 */
        if (docs.size() != 0) {
            /*  97 */
            List<Document> bs = new ArrayList<>();
            /*  98 */
            Double ruliangCount = Double.valueOf(0.0D);
            /*  99 */
            Document resDoc = docs.get(0);

            /* 101 */
            for (Document doc : docs) {
                try {
                    /* 103 */
                    String strVal = doc.getString("strVal");
                    /* 104 */
                    if (strVal != null && !strVal.isEmpty()) {
                        /* 105 */
                        Double value = Convert.toDouble(strVal, Double.valueOf(0.0D));

                        /* 107 */
                        value = DataUtils.roundDouble(value);
                        /* 108 */
                        ruliangCount = Double.valueOf(ruliangCount.doubleValue() + value.doubleValue());

                        /* 110 */
                        Date time = doc.getDate("time");
                        /* 111 */
                        String bedsideId = doc.getObjectId("_id").toString();
                        /* 112 */
                        Document b = new Document();
                        /* 113 */
                        b.put("strVal", strVal);
                        /* 114 */
                        b.put("time", DateUtil.format(time, "yyyy-MM-dd HH:mm:ss"));
                        /* 115 */
                        b.put("bedsideId", bedsideId);
                        /* 116 */
                        if (b != null) {
                            /* 117 */
                            bs.add(b);
                        }
                    }
                    /* 120 */
                } catch (Exception e) {
                    /* 121 */
                    log.error("CRStatistics,原因:" + e.toString());
                }
            }
            /* 124 */
            if (resDoc != null && !resDoc.isEmpty()) {
















                /* 141 */
                String strVal = String.valueOf(Math.round(ruliangCount.doubleValue()));




                /* 146 */
                resDoc.put("strVal", strVal);
                /* 147 */
                resDoc.put("countRecords", bs);
                /* 148 */
                resultDoc = resDoc;
            } else {
                /* 150 */
                resultDoc.put("strVal", "0");
                /* 151 */
                resultDoc.put("countRecords", new ArrayList());
            }
        } else {
            /* 154 */
            resultDoc.put("strVal", "0");
            /* 155 */
            resultDoc.put("countRecords", new ArrayList());
        }
        /* 157 */
        return resultDoc;
    }
}


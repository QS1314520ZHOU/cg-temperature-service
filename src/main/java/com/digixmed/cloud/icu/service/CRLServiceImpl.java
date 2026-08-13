
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
 public class CRLServiceImpl
        extends HandleService
        implements BaseService {
    /*  27 */   private static final Logger log = LoggerFactory.getLogger(CRLServiceImpl.class);

    @Value("${digixmed.timePointasd}")
    public Integer timePointasd;
    

    public IntermediateTable handle(Document bedside) {
        /*  34 */
        String bedSideId = ((ObjectId) getValueFromDocByKey(bedside, "_id", ObjectId.class)).toHexString();
        /*  35 */
        String pid = (String) getValueFromDocByKey(bedside, "pid", String.class);
        /*  36 */
        String code = (String) getValueFromDocByKey(bedside, "code", String.class);
        /*  37 */
        List<String> bedSideCodes = (List<String>) getValueFromDocByKey(bedside, "clCodes", List.class);

        /*  39 */
        Date startTime = (Date) getValueFromDocByKey(bedside, "time", Date.class);
        /*  40 */
        if (startTime == null) {
            /*  41 */
            return null;
        }
        /*  43 */
        Date endTime = DateUtils.getTomorrow(startTime);

        /*  45 */
        List<Document> documents = selectBedSidesRDXWithCode(startTime, endTime, pid, bedSideCodes);

        /*  47 */
        Document crlDoc = CRStatistics(documents, code);
        /*  48 */
        if (crlDoc == null) {
            /*  49 */
            return null;
        }

        /*  52 */
        IntermediateTable intermediateTable = new IntermediateTable();
        /*  53 */
        Document patient = queryPatientByPid(pid);
        /*  54 */
        String sideName = "";

        try {
            /*  57 */
            intermediateTable.setTimePoint(MyConfig.STARTTIME ? startTime : endTime);
            /*  58 */
            intermediateTable.setCreateTime((Date) getValueFromDocByKey(crlDoc, "editTime", Date.class));
            /*  59 */
            intermediateTable.setLastEditTime((Date) getValueFromDocByKey(crlDoc, "editTime", Date.class));
            /*  60 */
            intermediateTable.setSignValue((String) getValueFromDocByKey(crlDoc, "strVal", String.class));
            /*  61 */
            sideName = DataUtils.getSignNameByBedSideCode(code);
            /*  62 */
            intermediateTable.setSignName(sideName);
            /*  63 */
            intermediateTable.setSignCode(code);
            /*  64 */
            intermediateTable.setSignUnit(DataUtils.getUnitByCode(code));
            /*  65 */
            Boolean validBool = (Boolean) getValueFromDocByKey(crlDoc, "valid", Boolean.class);
            intermediateTable.setIsValid(validBool != null && validBool ? 1 : 0);
            /*  66 */
            intermediateTable.setMrn((String) getValueFromDocByKey(patient, "hisPid", String.class));
            /*  67 */
            intermediateTable.setZycs((String) getValueFromDocByKey(patient, "hospitalTime", String.class));
            /*  68 */
            intermediateTable.setPatientName((String) getValueFromDocByKey(patient, "name", String.class));
            /*  69 */
            intermediateTable.setPatientId((String) getValueFromDocByKey(patient, "mrn", String.class));
            /*  70 */
            intermediateTable.setIsFirst(Integer.valueOf(0));
            /*  71 */
            intermediateTable.setIsUpload(Integer.valueOf(0));
            /*  72 */
            String edituser = getTheAccountIdInTime(pid, startTime);
            /*  73 */
            intermediateTable.setAuthorName(queryDoctorById(edituser, Boolean.valueOf(true)));
            /*  74 */
            intermediateTable.setAuthorId(queryDoctorById(edituser, Boolean.valueOf(false)));
            /*  75 */
            intermediateTable.setBedSideId(bedSideId);
            /*  76 */
            intermediateTable.setPid((String) getValueFromDocByKey(crlDoc, "pid", String.class));
            /*  77 */
            intermediateTable.setChlidList((List) getValueFromDocByKey(crlDoc, "countRecords", List.class));
            /*  78 */
            intermediateTable = special(intermediateTable, pid, bedside);
            /*  79 */
        } catch (Exception e) {
            /*  80 */
            log.error("bedsideId为{}的体征：{} 报错，对应的记录时间为：{},原因:{}", new Object[]{sideName, startTime, e.toString()});
        }
        /*  82 */
        Date icuAdmissionTime = (Date) getValueFromDocByKey(patient, "icuAdmissionTime", Date.class);
        /*  83 */
        Date addmitTime = (icuAdmissionTime != null) ? DateUtils.formatTimePoint(icuAdmissionTime, this.timePointasd.intValue()) : null;
        /*  85 */
        if (addmitTime != null && intermediateTable.getTimePoint().before(addmitTime)) {
            /*  86 */
            return null;
        }
        /*  88 */
        return intermediateTable;
    }
    public IntermediateTable special(IntermediateTable table, String pid, Document bedside) {
        /*  93 */
        Date timePoint = DateUtils.getTimeByOclock(table.getTimePoint(), this.timePointasd);
        /*  94 */
        table.setTimePoint(timePoint);
        /*  95 */
        return table;
    }

    private Document CRStatistics(List<Document> docs, String code) {
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
                        /* 116 */
                        value = DataUtils.roundDouble(value);
                        /* 117 */
                        ruliangCount = Double.valueOf(ruliangCount.doubleValue() + value.doubleValue());
                        /* 119 */
                        Date time = doc.getDate("time");
                        /* 120 */
                        String bedsideId = doc.getObjectId("_id").toString();
                        /* 121 */
                        Document b = new Document();
                        /* 122 */
                        b.put("strVal", strVal);
                        /* 123 */
                        b.put("time", DateUtil.format(time, "yyyy-MM-dd HH:mm:ss"));
                        /* 124 */
                        b.put("bedsideId", bedsideId);
                        /* 125 */
                        b.put("code", doc.getString("code"));
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
                String strVal = String.valueOf(Math.round(ruliangCount.doubleValue()));
                /* 156 */
                resDoc.put("strVal", strVal);
                /* 157 */
                resDoc.put("countRecords", bs);
                /* 158 */
                resDoc.put("code", code);
                /* 159 */
                resultDoc = resDoc;
            } else {
                /* 161 */
                resultDoc.put("strVal", "0");
                /* 162 */
                resultDoc.put("countRecords", new ArrayList());
            }
        } else {
            /* 165 */
            resultDoc.put("strVal", "0");
            /* 166 */
            resultDoc.put("countRecords", new ArrayList());
        }
        /* 168 */
        return resultDoc;
    }
    
}

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
import org.springframework.stereotype.Service;

@Service
public class FaecesCountServiceImpl
        extends HandleService
        implements BaseService {
    /*  25 */   private static final Logger log = LoggerFactory.getLogger(FaecesCountServiceImpl.class);


    public IntermediateTable handle(Document bedside) {
        /*  30 */
        String bedSideId = ((ObjectId) getValueFromDocByKey(bedside, "_id", ObjectId.class)).toHexString();
        /*  31 */
        String pid = (String) getValueFromDocByKey(bedside, "pid", String.class);
        /*  32 */
        String code = (String) getValueFromDocByKey(bedside, "code", String.class);
        /*  33 */
        Date startTime = (Date) getValueFromDocByKey(bedside, "time", Date.class);
        /*  34 */
        if (startTime == null) {
            /*  35 */
            return null;
        }
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
        /*  46 */
        Date icuAdmissionTime = (Date) getValueFromDocByKey(patient, "icuAdmissionTime", Date.class);
        try {
            /*  48 */
            intermediateTable.setTimePoint(MyConfig.STARTTIME ? startTime : endTime);
            /*  49 */
            intermediateTable.setCreateTime((Date) getValueFromDocByKey(crlDoc, "editTime", Date.class));
            /*  50 */
            intermediateTable.setLastEditTime((Date) getValueFromDocByKey(crlDoc, "editTime", Date.class));
            /*  51 */
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
            intermediateTable.setMrn((String) getValueFromDocByKey(patient, "hisPid", String.class));
            /*  58 */
            intermediateTable.setZycs((String) getValueFromDocByKey(patient, "hospitalTime", String.class));
            /*  59 */
            intermediateTable.setPatientName((String) getValueFromDocByKey(patient, "name", String.class));
            /*  60 */
            intermediateTable.setPatientId((String) getValueFromDocByKey(patient, "mrn", String.class));
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
            /*  69 */
            intermediateTable = special(intermediateTable, pid, bedside);
            /*  70 */
        } catch (Exception e) {
            /*  71 */
            log.error("bedsideId为{}的体征：{} 报错，对应的记录时间为：{},原因:{}", new Object[]{sideName, endTime, e.toString()});
        }
        /*  73 */
        if (icuAdmissionTime != null && icuAdmissionTime.after(intermediateTable.getTimePoint())) {
            /*  74 */
            return null;
        }
        /*  76 */
        return intermediateTable;
    }


    public IntermediateTable special(IntermediateTable table, String pid, Document bedside) {
        /*  82 */
        return table;
    }


    private Document CRStatistics(List<Document> docs) {
        /*  92 */
        Document resultDoc = new Document();
        /*  93 */
        if (docs.size() != 0) {
            /*  94 */
            List<Document> bs = new ArrayList<>();
            /*  95 */
            Double ruliangCount = Double.valueOf(0.0D);
            /*  96 */
            Document resDoc = docs.get(0);

            /*  98 */
            for (Document doc : docs) {
                try {
                    /* 100 */
                    String strVal = doc.getString("strVal");
                    /* 101 */
                    if (strVal != null && !strVal.isEmpty()) {
                        /* 102 */
                        Double value = Convert.toDouble(strVal, Double.valueOf(0.0D));

                        /* 104 */
                        value = DataUtils.roundDouble(value);
                        /* 105 */
                        ruliangCount = Double.valueOf(ruliangCount.doubleValue() + value.doubleValue());

                        /* 107 */
                        Date time = doc.getDate("time");
                        /* 108 */
                        String bedsideId = doc.getObjectId("_id").toString();
                        /* 109 */
                        Document b = new Document();
                        /* 110 */
                        b.put("strVal", strVal);
                        /* 111 */
                        b.put("time", DateUtil.format(time, "yyyy-MM-dd HH:mm:ss"));
                        /* 112 */
                        b.put("bedsideId", bedsideId);
                        /* 113 */
                        if (b != null) {
                            /* 114 */
                            bs.add(b);
                        }
                    }
                    /* 117 */
                } catch (Exception e) {
                    /* 118 */
                    log.error("CRStatistics,原因:" + e.toString());
                }
            }
            /* 121 */
            if (resDoc != null && !resDoc.isEmpty()) {

                /* 123 */
                resDoc.put("strVal", Convert.toStr(Integer.valueOf(bs.size()), "0"));
                /* 124 */
                resDoc.put("countRecords", bs);
                /* 125 */
                resultDoc = resDoc;
            }
        }
        /* 128 */
        return resultDoc;
    }
}

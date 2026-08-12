package com.digixmed.cloud.icu.service.common;

import cn.hutool.core.util.ObjectUtil;
import com.digixmed.cloud.icu.dao.MongoDao;
import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.util.DataUtils;
import com.digixmed.cloud.icu.util.DateUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;


public abstract class HandleService {
    /*  23 */   private static final Logger log = LoggerFactory.getLogger(HandleService.class);


    @Autowired
    MongoDao mongoDao;


    /*  30 */   public ConcurrentHashMap<String, Document> accountMap = new ConcurrentHashMap<>();


    public <T> T getValueFromDocByKey(Document doc, String path, Class<T> clazz) {
        /*  34 */
        if (doc == null) {
            /*  35 */
            return null;
        }
        /*  37 */
        Object result = null;
        try {
            /*  39 */
            if (path.contains(".")) {
                /*  40 */
                String[] keys = path.split("\\.");
                /*  41 */
                for (String key : keys) {
                    /*  42 */
                    if (doc.get(key) == null) {
                        break;
                    }
                    /*  45 */
                    if (doc.get(key) instanceof Document) {
                        /*  46 */
                        doc = (Document) doc.get(key, Document.class);
                    } else {
                        /*  48 */
                        result = doc.get(key, clazz);
                    }
                }
            } else {
                /*  52 */
                result = doc.get(path, clazz);
            }

            /*  55 */
        } catch (Exception e) {
            /*  56 */
            log.error(path + "报错：" + path);
        }
        /*  58 */
        if (result != null) {
            /*  59 */
            if (result instanceof String && (
                    /*  60 */         (String) result).isEmpty()) {
                /*  61 */
                return null;
            }

            /*  64 */
            return (T) result;
        }
        /*  66 */
        return null;
    }


    public <T> T getValueFromDocByKeyWithDefaultValue(Document doc, String path, Class<T> clazz, Object defaultValue) {
        /*  71 */
        if (doc == null) {
            /*  72 */
            return (T) defaultValue;
        }
        /*  74 */
        Object result = defaultValue;
        try {
            /*  76 */
            if (path.contains(".")) {
                /*  77 */
                String[] keys = path.split("\\.");
                /*  78 */
                for (String key : keys) {
                    /*  79 */
                    if (doc.get(key) == null) {
                        break;
                    }
                    /*  82 */
                    if (doc.get(key) instanceof Document) {
                        /*  83 */
                        doc = (Document) doc.get(key, Document.class);
                    } else {
                        /*  85 */
                        result = doc.get(key, clazz);
                    }
                }
            } else {
                /*  89 */
                result = doc.get(path, clazz);
            }

            /*  92 */
        } catch (Exception e) {
            /*  93 */
            log.error(path + "报错：" + path);
        }
        /*  95 */
        if (result != null) {
            /*  96 */
            if (result instanceof String && (
                    /*  97 */         (String) result).isEmpty()) {
                /*  98 */
                return null;
            }

            /* 101 */
            return (T) result;
        }
        /* 103 */
        return (T) defaultValue;
    }


    public String queryDoctorById(String accountId, Boolean isName) {
        /* 113 */
        Document account = null;
        /* 114 */
        String result = null;
        /* 115 */
        if (accountId != null && this.accountMap.contains(accountId)) {
            /* 116 */
            account = this.accountMap.get(accountId);
        } else {
            /* 118 */
            account = this.mongoDao.selectDoctorByAccountId(accountId);
            /* 119 */
            if (account != null) {
                /* 120 */
                this.accountMap.put(accountId, account);
            }
        }
        /* 123 */
        if (account != null) {
            /* 124 */
            result = isName.booleanValue() ? getValueFromDocByKey(account, "trueName", String.class) : getValueFromDocByKey(account, "username", String.class);
        }
        /* 126 */
        return result;
    }


    public Document queryPatientByPid(String pid) {
        /* 136 */
        return this.mongoDao.selectPatientByPid(pid);
    }

    public String getTheAccountIdInTime(String pid, Date timePoint) {
        /* 140 */
        return this.mongoDao.getTheAccountIdInTime(pid, timePoint);
    }

    public Boolean queryHRWithTime(String pid, Date time) {
        /* 144 */
        return Boolean.valueOf((this.mongoDao.selectBedSideByCodeAndTime(pid, "param_HR", time) != null));
    }

    public Document queryHuXiPinLv(String pid, Date time, String code) {
        /* 148 */
        return this.mongoDao.selectVaildBedSideByCodeAndTime(pid, code, time);
    }

    public Document queryIBPS(String pid, Date time, String code) {
        /* 152 */
        return this.mongoDao.selectVaildBedSideByCodeAndTime(pid, code, time);
    }

    public IntermediateTable handle(Document bedside) {
        /* 156 */
        IntermediateTable intermediateTable = new IntermediateTable();
        /* 157 */
        String sideName = "";
        /* 158 */
        Date timePoint = null;
        /* 159 */
        String bedSideId = ((ObjectId) getValueFromDocByKey(bedside, "_id", ObjectId.class)).toHexString();
        /* 160 */
        String pid = getValueFromDocByKey(bedside, "pid", String.class);
        /* 161 */
        Document patient = queryPatientByPid(pid);
        /* 162 */
        Date icuAdmissionTime = getValueFromDocByKey(patient, "icuAdmissionTime", Date.class);
        try {
            /* 164 */
            timePoint = getValueFromDocByKey(bedside, "time", Date.class);
            /* 165 */
            intermediateTable.setTimePoint(timePoint);
            /* 166 */
            intermediateTable.setCreateTime(getValueFromDocByKey(bedside, "editTime", Date.class));
            /* 167 */
            intermediateTable.setLastEditTime(getValueFromDocByKey(bedside, "editTime", Date.class));
            /* 168 */
            intermediateTable.setSignValue(getValueFromDocByKey(bedside, "strVal", String.class));
            /* 169 */
            String code = getValueFromDocByKey(bedside, "code", String.class);
            /* 170 */
            sideName = DataUtils.getSignNameByBedSideCode(code);
            /* 171 */
            intermediateTable.setSignName(sideName);
            /* 172 */
            intermediateTable.setSignCode(code);
            /* 173 */
            intermediateTable.setSignUnit(DataUtils.getUnitByCode(code));
            /* 174 */
            Boolean validBool = (Boolean) getValueFromDocByKey(bedside, "valid", Boolean.class);
            intermediateTable.setIsValid(validBool != null && validBool ? 1 : 0);
            /* 175 */
            intermediateTable.setMrn(getValueFromDocByKey(patient, "mrn", String.class));
            /* 176 */
            intermediateTable.setZycs(getValueFromDocByKey(patient, "hospitalTime", String.class));
            /* 177 */
            intermediateTable.setPatientName(getValueFromDocByKey(patient, "name", String.class));
            /* 178 */
            intermediateTable.setPatientId(getValueFromDocByKey(patient, "hisPid", String.class));
            /* 179 */
            intermediateTable.setIsFirst(Integer.valueOf(0));
            /* 180 */
            intermediateTable.setIsUpload(Integer.valueOf(0));
            /* 181 */
            String edituser = getTheAccountIdInTime(pid, timePoint);
            /* 182 */
            intermediateTable.setAuthorName(queryDoctorById(edituser, Boolean.valueOf(true)));
            /* 183 */
            intermediateTable.setAuthorId(queryDoctorById(edituser, Boolean.valueOf(false)));
            /* 184 */
            intermediateTable.setBedSideId(bedSideId);
            /* 185 */
            intermediateTable.setPid(pid);
            /* 186 */
            intermediateTable = special(intermediateTable, pid, bedside);
            /* 187 */
        } catch (Exception e) {
            /* 188 */
            log.error("bedsideId为{}的体征：{} 报错，对应的记录时间为：{},原因:{}", new Object[]{sideName, timePoint, e.toString()});
        }
        /* 190 */
        if (icuAdmissionTime != null && ObjectUtil.isNotEmpty(intermediateTable) && icuAdmissionTime.after(intermediateTable.getTimePoint())) {
            /* 191 */
            return null;
        }
        /* 193 */
        return intermediateTable;
    }

    public IntermediateTable handle4First(Document bedside) {
        /* 197 */
        IntermediateTable table = handle(bedside);
        /* 198 */
        if (table != null) {
            /* 199 */
            table.setIsFirst(Integer.valueOf(1));

            /* 201 */
            Date timePoint1 = table.getTimePoint();
            /* 202 */
            if (ObjectUtil.isNotEmpty(timePoint1)) {
                /* 203 */
                Date timePoint = DateUtils.getFirstTime(timePoint1);
                /* 204 */
                table.setTimePoint(timePoint);
            }
        }
        /* 207 */
        return table;
    }

    public List<Document> selectBedSidesRDXWithCode(Date RDXlastTime, Date RDXthisTime, String pid, String code) {
        /* 211 */
        return this.mongoDao.selectBedSidesRDXWithCode(RDXlastTime, RDXthisTime, pid, code);
    }

    public List<Document> selectBedSidesRDXWithCode(Date RDXlastTime, Date RDXthisTime, String pid, String[] codes) {
        /* 215 */
        if (codes == null) return new ArrayList<>();
        /* 216 */
        return this.mongoDao.selectBedSidesRDXWithCode(RDXlastTime, RDXthisTime, pid, codes);
    }

    public List<Document> selectBedSidesRDXWithCode(Date RDXlastTime, Date RDXthisTime, String pid, List<String> codes) {
        /* 219 */
        if (codes == null) return new ArrayList<>();
        /* 220 */
        return this.mongoDao.selectBedSidesRDXWithCode(RDXlastTime, RDXthisTime, pid, codes);
    }


    public Boolean isUrinaryTube(String pid, Date leftTime) {
        /* 225 */
        if (pid == null) {
            /* 226 */
            return Boolean.valueOf(false);
        }
        /* 228 */
        Boolean result = Boolean.valueOf(false);
        /* 229 */
        List<Document> tubes = this.mongoDao.getTube(pid);

        /* 231 */
        Date rightTime = DataUtils.getTomorrow7clock(leftTime);
        /* 232 */
        if (tubes == null || tubes.size() == 0) {
            /* 233 */
            return Boolean.valueOf(false);
        }
        /* 235 */
        for (Document tube : tubes) {
            try {
                /* 237 */
                Date startTime = tube.getDate("startTime");
                /* 238 */
                Date endTime = tube.getDate("endTime");
                /* 239 */
                if (startTime == null) {
                    continue;
                }
                /* 242 */
                if (endTime != null) {
                    /* 243 */
                    result = Boolean.valueOf((DateUtils.isEffectiveDate(leftTime, startTime, endTime) || DateUtils.isEffectiveDate(rightTime, startTime, endTime)));
                } else {
                    /* 245 */
                    result = Boolean.valueOf((DateUtils.isEffectiveDate(leftTime, startTime, new Date()) || DateUtils.isEffectiveDate(rightTime, startTime, new Date())));
                }
                /* 247 */
                if (result.booleanValue()) {
                    break;
                }
                /* 250 */
            } catch (Exception e) {
                /* 251 */
                log.error("判断尿量失败：" + pid + ",时间为：" + leftTime + ",原因" + e);
            }
        }
        /* 254 */
        return result;
    }


    public Integer determineFaecesType(String pid, Date startTime) {
        /* 265 */
        Date endTime = DateUtils.getTomorrow(startTime);
        /* 266 */
        List<Document> faecesTypes = this.mongoDao.selectBedSidesRDXWithCode(startTime, endTime, pid, "param_daBianCiShu");
        /* 267 */
        Boolean firstType = Boolean.valueOf(faecesTypes.stream().anyMatch(type -> ("灌肠".equals(getValueFromDocByKeyWithDefaultValue(type, "strVal", String.class, "")) || "人工肛".equals(getValueFromDocByKeyWithDefaultValue(type, "strVal", String.class, "")))));

        /* 269 */
        if (firstType.booleanValue()) {
            /* 270 */
            return Integer.valueOf(1);
        }
        /* 272 */
        Boolean secondType = Boolean.valueOf(faecesTypes.stream().anyMatch(type -> "失禁".equals(getValueFromDocByKeyWithDefaultValue(type, "strVal", String.class, ""))));
        /* 273 */
        if (secondType.booleanValue()) {
            /* 274 */
            return Integer.valueOf(2);
        }
        /* 276 */
        return Integer.valueOf(0);
    }


    public Document selectBedSideByTimeAndIdAndCode(String pid, Date time, String code) {
        /* 282 */
        return this.mongoDao.selectBedSideByTimeAndIdAndCode(pid, code, time);
    }


    public Boolean updateAndSaveIntermediateTable(IntermediateTable tableInfo) {
        /* 292 */
        if (tableInfo == null) {
            /* 293 */
            return Boolean.valueOf(false);
        }
        /* 295 */
        if (tableInfo.getSignValue() == null) {
            /* 296 */
            log.info(tableInfo.getPatientName() + "," + tableInfo.getPatientName() + "的体征值为空不进行回传");
            /* 297 */
            return Boolean.valueOf(false);
        }
        /* 299 */
        IntermediateTable lastTableInfo = this.mongoDao.selectIntermediateTableByTimeAndIdAndCode(tableInfo.getMrn(), tableInfo.getSignCode(), tableInfo.getTimePoint());
        /* 300 */
        if (lastTableInfo == null) {
            /* 301 */
            this.mongoDao.saveIntermediateTable(tableInfo);
            /* 302 */
            log.info("新增体征：" + tableInfo.getPatientName() + "," + tableInfo.getSignName() + "," + tableInfo.getSignValue() + "," + DateUtils.parseTime(tableInfo.getTimePoint()));

        }
        /* 305 */
        else if (compareStrValue(tableInfo, lastTableInfo).booleanValue()) {
            /* 306 */
            log.info("更新体征：" + tableInfo.getPatientName() + "," + tableInfo.getSignName() + "," + tableInfo.getSignValue() + "," + DateUtils.parseTime(tableInfo.getTimePoint()) + ",重新上传");
            /* 307 */
            this.mongoDao.updateIntermediateTable(lastTableInfo, tableInfo);
        }

        /* 310 */
        return Boolean.valueOf(true);
    }


    public Boolean compareStrValue(IntermediateTable tableInfo, IntermediateTable lastTableInfo) {
        /* 315 */
        if (ObjectUtil.isEmpty(tableInfo)) return Boolean.valueOf(false);
        /* 316 */
        if (ObjectUtil.isEmpty(lastTableInfo)) return Boolean.valueOf(false);
        /* 317 */
        if (lastTableInfo.getIsValid() != null && lastTableInfo.getIsValid().equals(tableInfo.getIsValid()) &&
                /* 318 */       tableInfo.getSignValue().equals(lastTableInfo.getSignValue())) {
            /* 319 */
            log.info("病人：{},同一体征的数值相同，不需要回传！体征：{},体征值：{},时间点:{}", new Object[]{tableInfo.getPatientName(), tableInfo.getSignName(), tableInfo
                    /* 320 */.getSignValue(), tableInfo.getTimePoint()});
            /* 321 */
            return Boolean.valueOf(false);
        }


        /* 325 */
        return Boolean.valueOf(true);
    }

    public abstract IntermediateTable special(IntermediateTable paramIntermediateTable, String paramString, Document paramDocument);
}


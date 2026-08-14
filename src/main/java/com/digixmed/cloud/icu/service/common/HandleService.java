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
            /* 体温复测值：原始值>=38.5 时查 1 小时内 bedside 复测记录，仍>=38.5 则写入 signValue2，否则为空 */
            intermediateTable.setSignValue2(resolveRecheckTemperature(code, pid, timePoint, bedSideId,
                    getValueFromDocByKey(bedside, "strVal", String.class)));
            /* 初始化复测巡检状态：高热且本次未取到复测值时，由 TemperatureRecheckTask 每10分钟继续查找 */
            initRecheckState(intermediateTable, code, getValueFromDocByKey(bedside, "strVal", String.class));
            /* 体温部位（vitalsignSVal1）：取对应时间节点 param_tiWenBuWei 的 strVal */
            intermediateTable.setSignLocation(resolveTemperatureLocation(code, pid, timePoint));
            /* 174 */
            Boolean validBool = (Boolean) getValueFromDocByKey(bedside, "valid", Boolean.class);
            intermediateTable.setIsValid(validBool != null && validBool ? 1 : 0);
            /* 175 */
            intermediateTable.setPatientId(getValueFromDocByKey(patient, "mrn", String.class));
            /* 176 */
            intermediateTable.setZycs(getValueFromDocByKey(patient, "hospitalTime", String.class));
            /* 177 */
            intermediateTable.setPatientName(getValueFromDocByKey(patient, "name", String.class));
            /* 178 */
            intermediateTable.setMrn(getValueFromDocByKey(patient, "hisPid", String.class));
            /* 179 */
            intermediateTable.setIsFirst(Integer.valueOf(0));
            /* 180 */
            intermediateTable.setIsUpload(Integer.valueOf(0));
            /* 181 */
            String edituser = resolveRecordNurseAccountId(pid, timePoint, bedside);
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
                /* 318 */       tableInfo.getSignValue().equals(lastTableInfo.getSignValue()) &&
                /* 复测值也必须相同，否则只新增了复测值时会被当作“无变化”而不回传 */
                isSameSignValue2(tableInfo, lastTableInfo)) {
            /* 319 */
            log.info("病人：{},同一体征的数值相同，不需要回传！体征：{},体征值：{},时间点:{}", new Object[]{tableInfo.getPatientName(), tableInfo.getSignName(), tableInfo
                    /* 320 */.getSignValue(), tableInfo.getTimePoint()});
            /* 321 */
            return Boolean.valueOf(false);
        }


        /* 325 */
        return Boolean.valueOf(true);
    }

    /** 体温复测阈值 */
    protected static final double TEMPERATURE_RECHECK_THRESHOLD = 38.5D;

    /** 体温对应的 bedside code */
    protected static final String TEMPERATURE_CODE = "param_T";

    /**
     * 计算体温复测值：
     * 原始值（NVal1）>=38.5 才需复测；查 bedside.time 往后 1 小时内的 param_T 记录，
     * 取第一个仍>=38.5 的值作为复测值；否则返回 null（回传时转空串）。
     */
    protected String resolveRecheckTemperature(String code, String pid, Date timePoint,
                                              String excludeBedsideId, String signValue) {
        if (!TEMPERATURE_CODE.equals(code) || pid == null || timePoint == null) {
            return null;
        }
        Double origin = parseTemperature(signValue);
        if (origin == null || origin.doubleValue() < TEMPERATURE_RECHECK_THRESHOLD) {
            return null;
        }
        List<Document> recheckDocs = this.mongoDao.selectRecheckTemperature(pid, timePoint, excludeBedsideId);
        if (recheckDocs == null || recheckDocs.isEmpty()) {
            log.info("体温复测：pid={},原始值={},1小时内无复测记录", new Object[]{pid, signValue});
            return null;
        }
        for (Document doc : recheckDocs) {
            String value = getValueFromDocByKey(doc, "strVal", String.class);
            Double recheck = parseTemperature(value);
            if (recheck != null && recheck.doubleValue() >= TEMPERATURE_RECHECK_THRESHOLD) {
                log.info("体温复测：pid={},原始值={},复测值={}", new Object[]{pid, signValue, value});
                return value.trim();
            }
        }
        log.info("体温复测：pid={},原始值={},复测值低于阈值，不传 NVal2", new Object[]{pid, signValue});
        return null;
    }

    protected Double parseTemperature(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(Double.parseDouble(trimmed));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 复测值是否一致（null 与空串等价） */
    protected boolean isSameSignValue2(IntermediateTable tableInfo, IntermediateTable lastTableInfo) {
        String current = (tableInfo == null || tableInfo.getSignValue2() == null) ? "" : tableInfo.getSignValue2().trim();
        String last = (lastTableInfo == null || lastTableInfo.getSignValue2() == null) ? "" : lastTableInfo.getSignValue2().trim();
        return current.equals(last);
    }

    /**
     * 初始化体温复测巡检状态。
     * 仅当 param_T 且原始值>=38.5、且当前还没有复测值时，才需要后续每10分钟巡检（最多6次）。
     */
    protected void initRecheckState(IntermediateTable table, String code, String signValue) {
        if (table == null) {
            return;
        }
        table.setRecheckAttempts(Integer.valueOf(0));
        boolean needRecheck = false;
        if (TEMPERATURE_CODE.equals(code)) {
            Double origin = parseTemperature(signValue);
            String exist = table.getSignValue2();
            needRecheck = (origin != null && origin.doubleValue() >= TEMPERATURE_RECHECK_THRESHOLD
                    && (exist == null || exist.trim().isEmpty()));
        }
        table.setRecheckDone(Integer.valueOf(needRecheck ? 0 : 1));
    }

    /** 记录护士对应的 bedside code */
    protected static final String NURSE_CODE = "param_Yishi";

    /** 体温部位对应的 bedside code */
    protected static final String TEMPERATURE_LOCATION_CODE = "param_tiWenBuWei";

    /** 读取 editUser（兼容 String 与 ObjectId 两种存储形式） */
    protected String readEditUser(Document doc) {
        if (doc == null) {
            return null;
        }
        Object editUser = doc.get("editUser");
        if (editUser == null) {
            return null;
        }
        String value = editUser.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 解析记录护士对应的 account id（recordNurseName / recordNurseId 的来源）：
     *   1. 优先取对应时间节点 bedside.code=param_Yishi 的 editUser；
     *   2. 没有 param_Yishi 时，取当前体征记录（如 param_T）自身的 editUser；
     *   3. 两者都没有时，回退到排班记录（ShiftRecord）中该时间点的护士。
     * editUser 存的是 account._id，后续由 queryDoctorById 查 account.trueName / account.username。
     */
    protected String resolveRecordNurseAccountId(String pid, Date timePoint, Document bedside) {
        String editUser = null;
        if (pid != null && timePoint != null) {
            Document nurseDoc = this.mongoDao.selectBedSideByCodeAndTimePoint(pid, NURSE_CODE, timePoint);
            editUser = readEditUser(nurseDoc);
            if (editUser != null) {
                log.info("记录护士：pid={},时间点={},来源=param_Yishi,editUser={}", new Object[]{pid, timePoint, editUser});
            }
        }
        if (editUser == null) {
            editUser = readEditUser(bedside);
            if (editUser != null) {
                log.info("记录护士：pid={},时间点={},无 param_Yishi，取本体征记录 editUser={}", new Object[]{pid, timePoint, editUser});
            }
        }
        if (editUser == null) {
            editUser = getTheAccountIdInTime(pid, timePoint);
            log.info("记录护士：pid={},时间点={},无 param_Yishi 且本记录无 editUser，回退排班 accountId={}", new Object[]{pid, timePoint, editUser});
        }
        return (editUser == null || editUser.trim().isEmpty()) ? null : editUser.trim();
    }

    /** 体温部位：取对应时间节点 param_tiWenBuWei 的 strVal，无数据返回 null */
    protected String resolveTemperatureLocation(String code, String pid, Date timePoint) {
        if (!TEMPERATURE_CODE.equals(code) || pid == null || timePoint == null) {
            return null;
        }
        Document doc = this.mongoDao.selectBedSideByCodeAndTimePoint(pid, TEMPERATURE_LOCATION_CODE, timePoint);
        String location = (doc == null) ? null : getValueFromDocByKey(doc, "strVal", String.class);
        if (location == null || location.trim().isEmpty()) {
            log.info("体温部位：pid={},时间点={},未找到 param_tiWenBuWei 数据", new Object[]{pid, timePoint});
            return null;
        }
        log.info("体温部位：pid={},时间点={},部位={}", new Object[]{pid, timePoint, location.trim()});
        return location.trim();
    }

    public abstract IntermediateTable special(IntermediateTable paramIntermediateTable, String paramString, Document paramDocument);
}


package com.digixmed.cloud.icu.service.common;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.digixmed.cloud.icu.dao.MongoDao;
import com.digixmed.cloud.icu.pojo.Data;
import com.digixmed.cloud.icu.pojo.DataValue;
import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.util.DataUtils;
import com.digixmed.cloud.icu.util.HttpUtils;
import com.digixmed.cloud.icu.util.ResponseUtils;
import com.digixmed.cloud.icu.util.XMLUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReturnService {
    /*  29 */   private static final Logger log = LoggerFactory.getLogger(ReturnService.class);


    @Resource
    MongoDao mongoDao;


    @Value("${digixmed.url}")
    public String url;

    @Value("${digixmed.timePointasd}")
    public Integer timePointasd;

    @Value("${digixmed.debug}")
    public Boolean debug;

    @Value("${digixmed.isUpload}")
    public Boolean isUpload;

    @Value("${digixmed.testPid}")
    public String testPid;


    public void uploadInfo() {
        /*  53 */
        Date now = new Date();
        /*  54 */
        if (this.isUpload.booleanValue()) {
            /*  55 */
            log.info(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "进行回传");
            /*  56 */
            log.info("url:" + this.url + "---isUpload:" + this.isUpload + "-----testPid" + this.testPid);
            /*  57 */
            int count = 0;
            /*  58 */
            List<IntermediateTable> tableInfos = new ArrayList<>();
            /*  59 */
            if ("all".equals(this.testPid)) {
                /*  60 */
                tableInfos = this.mongoDao.selectNoUploadInfo();
                /*  61 */
            } else if (this.testPid != null) {
                /*  62 */
                String[] split = this.testPid.split(",");
                /*  63 */
                tableInfos = this.mongoDao.selectNoUploadInfo(split);
            }
            /*  65 */
            if (tableInfos.size() == 0) {
                /*  66 */
                log.info(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "--tableInfos为空");

                return;
            }
            /*  70 */
            for (IntermediateTable tableInfo : tableInfos) {
                /*  71 */
                Data data = new Data();
                /*  72 */
                List<DataValue> dataValues = new ArrayList<>();

                /*  74 */
                if (!this.debug.booleanValue() &&
                        /*  75 */           tableInfo.getTimePoint() != null && tableInfo.getTimePoint().after(now)) {
                    /*  76 */
                    log.info("记录时间不得超过当前时间!当前时间:{},记录时间:{},记录:{},", new Object[]{DateUtil.format(now, "yyyy-MM-dd HH:mm:ss"), DateUtil.format(tableInfo.getTimePoint(), "yyyy-MM-dd HH:mm:ss"), tableInfo.toString()});

                    continue;
                }
                /*  80 */
                String patientId = tableInfo.getPatientId();


                // 准入原则：只回传 patient 集合中存在的 _id；不存在直接跳过，
                // 不再判断金仓在科状态，也不再按待出科/已出科拦截出入量记录。
                Document patientDoc = this.mongoDao.getPatientInfoSafely(tableInfo.getPid());
                if (patientDoc == null) {
                    log.info("病人pid={}在patient集合中不存在，{}记录不回传", new Object[]{tableInfo.getPid(), tableInfo.getSignCode()});
                    continue;
                }

                // 历史数据可能未落库病人标识，回传前用 patient 文档补齐：
                // patientId = patient.mrn，patientName = patient.name，mrn = patient.hisPid
                String docPatientId = readString(patientDoc, "mrn");
                String docPatientName = readString(patientDoc, "name");
                String docMrn = readString(patientDoc, "hisPid");
                if (docPatientId != null) {
                    tableInfo.setPatientId(docPatientId);
                }
                if (docPatientName != null) {
                    tableInfo.setPatientName(docPatientName);
                }
                if (docMrn != null) {
                    tableInfo.setMrn(docMrn);
                }
                if (ObjectUtil.isEmpty(tableInfo.getMrn())) {
                    log.warn("病人pid={}的patient.hisPid为空，mrn入参将为空串", new Object[]{tableInfo.getPid()});
                }
                patientId = tableInfo.getPatientId();
                if (ObjectUtil.isEmpty(patientId)) {
                    log.info("病人pid={}的patient.mrn为空，patientId无法赋值，{}记录不回传", new Object[]{tableInfo.getPid(), tableInfo.getSignCode()});
                    continue;
                }

                /* 107 */
                DataValue dataValue = tableToData(tableInfo);
                /* 108 */
                if (dataValue == null) {
                    continue;
                }
                if (data != null) {
                    /* 109 */
                    dataValues.add(dataValue);
                }
                /* 111 */
                data.setData(dataValues);

                /* 113 */
                String dataStr = XMLUtils.convertToXml(data);
                /* 114 */
                String requestStr = DataUtils.getRequestStr(dataStr);
                /* 115 */
                if (this.isUpload.booleanValue()) {
                    /* 116 */
                    log.info(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "开始组装回传");

                    /* 118 */
                    Map<String, String> resultMap = upload(requestStr);
                    /* 119 */
                    String msg = resultMap.get("msg");
                    /* 120 */
                    log.info("请求的报文为:" + dataStr + ",响应的报文为:" + msg);
                    /* 121 */
                    tableInfo.setRequestMsg(dataStr);
                    /* 122 */
                    tableInfo.setReponseMsg(msg);

                    /* 124 */
                    tableInfo.setReturnTime(new Date());
                    /* 125 */
                    log.info(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "回传结束");
                    /* 126 */
                    if ("200".equals(resultMap.get("code"))) {
                        /* 127 */
                        if (ResponseUtils.isBusinessSuccess(msg)) {
                            /* 128 */
                            updateUploadLog(tableInfo, Boolean.valueOf(true), null);
                            continue;
                        }
                        /* 130 */
                        String errorMsg = StringUtils.substringBetween(msg, "<msg>", "</msg>");
                        /* 131 */
                        updateUploadLog(tableInfo, Boolean.valueOf(false), errorMsg);
                        continue;
                    }
                    /* 134 */
                    updateUploadLog(tableInfo, Boolean.valueOf(false), msg);
                    /* 135 */
                    log.error("回传失败" + dataValues.size() + "条体征记录，" + requestStr);
                    continue;
                }
                /* 138 */
                log.warn("回传的配置未打开,待回传的报文为：" + dataStr);
            }

            /* 141 */
            log.info("成功回传了{}病人的{}条体征记录", this.testPid, Integer.valueOf(count));
        } else {
            /* 143 */
            log.warn("测试时，不进行回传!");
        }
        /* 145 */
        log.info(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "结束回传");
    }


    public Map<String, String> upload(String dataStr) {
        /* 168 */
        Map<String, String> result = new HashMap<>();
        try {
            /* 170 */
            result = HttpUtils.doPost(this.url, dataStr);
            /* 171 */
        } catch (Exception e) {
            /* 172 */
            result.put("code", "404");
            /* 173 */
            result.put("result", "请求失败");
            /* 174 */
            result.put("success", "false");
            /* 175 */
            result.put("msg", "请求接口失败！原因：" + e.getMessage());
        }
        /* 177 */
        return result;
    }

    public DataValue tableToData(IntermediateTable tableInfo) {
        /* 181 */
        DataValue data = new DataValue();

        try {
            // 跳过新流程记录（已有requestBodyMasked/responseBodyMasked的记录已由PushTask处理）
            if (tableInfo.getRequestBodyMasked() != null || tableInfo.getResponseBodyMasked() != null) {
                return null;
            }
            // 跳过signCode为空的记录（新流程记录没有signCode字段）
            if (tableInfo.getSignCode() == null) {
                return null;
            }

            /* 184 */
            String vitalsignType = DataUtils.getSignCodeByCode(tableInfo.getSignCode());
            /* 185 */
            Integer valid = Integer.valueOf(0);

            /* 187 */
            if (tableInfo.getIsValid() != null) {
                /* 188 */
                valid = tableInfo.getIsValid();
            }
            /* 190 */
            data.setIsValid(valid.intValue());
            /* 191 */
            // mrn 取自 patient.hisPid；null 会导致 JAXB 直接省略 <mrn> 节点，因此空值统一输出空串
            data.setMrn(tableInfo.getMrn() != null ? tableInfo.getMrn() : "");
            /* 192 */
            // patientId 取自 patient.mrn
            data.setPatientId(tableInfo.getPatientId() != null ? tableInfo.getPatientId() : "");
            /* 193 */
            // patientName 取自 patient.name
            data.setPatientName(tableInfo.getPatientName() != null ? tableInfo.getPatientName() : "");
            /* 194 */
            // recordNurseId 为空时默认 "dba"
            String nurseId = tableInfo.getAuthorId();
            data.setRecordNurseId((nurseId != null && !nurseId.isEmpty()) ? nurseId : "dba");
            /* 195 */
            // recordNurseName：优先 param_Yishi.editUser -> account.trueName，其次本体征记录 editUser -> account.trueName，均取不到时默认 "系统管理员"
            String nurseName = resolveRecordNurseName(tableInfo);
            data.setRecordNurseName((nurseName != null && !nurseName.isEmpty()) ? nurseName : "系统管理员");
            /* 196 */
            data.setWardCode("125011");
            /* 197 */
            data.setPlanTime(tableInfo.getTimePoint());
            /* 198 */
            data.setRecordTime(tableInfo.getTimePoint());
            /* 199 */
            data.setRemark("");
            /* 200 */
            data.setSeries(tableInfo.getZycs());
            /* 201 */
            data.setUnit(tableInfo.getSignUnit());
            /* 202 */
            String signValue = tableInfo.getSignValue();
            if (signValue == null) {
                signValue = "";
            }


            /* 205 */
            if ("param_niaoLiang".equals(tableInfo.getSignCode())) {
                /* 206 */
                data.setVitalsignSVal1(signValue);
            }
            /* 208 */
            else if (MyConfig.CODES_XUEYA.contains(tableInfo.getSignCode())) {
                /* 209 */
                String[] signValues = signValue.split("/");
                /* 210 */
                data.setVitalsignNVal1(signValues[0]);
                /* 211 */
                if (signValues.length > 1) {
                    data.setVitalsignNVal2(signValues[1]);
                }
                /* 212 */
            } else if ("param_daBianCiShu".contains(tableInfo.getSignCode()) || "param_daBianAmount".contains(tableInfo.getSignCode())) {
                /* 213 */
                data.setVitalsignSVal1(signValue);


            }
            /* 218 */
            else if (MyConfig.CODES[0].contains(tableInfo.getSignCode())) {
                /* 219 */
                data.setVitalsignNVal1(signValue);
                /* 220 */
                /* vitalsignSVal1：对应时间节点 param_tiWenBuWei 的 strVal */
                data.setVitalsignSVal1(resolveTemperatureLocation(tableInfo));
                /* 体温复测值：无复测时固定传空串，保证报文中始终存在 <vitalsignNVal2> 节点 */
                data.setVitalsignNVal2(resolveRecheckValue(tableInfo, signValue));
                /* 221 */
            } else if (MyConfig.CODES[1].contains(tableInfo.getSignCode())) {
                /* 222 */
                data.setVitalsignNVal1(signValue);
                /* 223 */
            } else if ("param_PR".contains(tableInfo.getSignCode())) {
                /* 224 */
                data.setVitalsignNVal1(signValue);
                /* 225 */
            } else if (MyConfig.CODES[2].contains(tableInfo.getSignCode())) {
                /* 226 */
                data.setVitalsignNVal1(signValue);
                /* 227 */
                if (tableInfo.getInHuXiJi() != null && tableInfo.getInHuXiJi() != 0) {
                    /* 228 */
                    data.setVitalsignSVal1("呼吸机");
                } else {
                    /* 230 */
                    data.setVitalsignSVal1("");
                }
                /* 232 */
            } else if ("param_in_hour_sum".contains(tableInfo.getSignCode()) || "param_out_hour_sum"
/* 233 */.contains(tableInfo.getSignCode()) || "param_out_other"
/* 234 */.contains(tableInfo.getSignCode())) {
                /* 235 */
                data.setVitalsignSVal1(signValue);
            }
            /* 237 */
            data.setVitalsignName(tableInfo.getSignName());
            /* 238 */
            data.setVitalsignType(vitalsignType);
            /* 239 */
        } catch (Exception e) {
            /* 240 */
            log.error("{},转换报错：{}", tableInfo.toString(), e.toString());
        }
        /* 242 */
        return data;
    }


    /**
     * 是否为出入量类记录（尿量、大便、入量/出量汇总等）。
     * 只有这类记录在病人待出科/出科后需要停止回传，体温、心率、呼吸、血压等不受影响。
     */
    private boolean isInOutCode(String signCode) {
        if (signCode == null) {
            return false;
        }
        for (String code : MyConfig.NLCODES) {
            if (code.equals(signCode)) {
                return true;
            }
        }
        for (String code : MyConfig.DBCODES) {
            if (code.equals(signCode)) {
                return true;
            }
        }
        for (String code : MyConfig.BEISIDE_CODES_DABIAN) {
            if (code.equals(signCode)) {
                return true;
            }
        }
        for (String code : MyConfig.CLCODES) {
            if (code.equals(signCode)) {
                return true;
            }
        }
        for (String code : MyConfig.RLCODES) {
            if (code.equals(signCode)) {
                return true;
            }
        }
        return "param_in_hour_sum".equals(signCode)
                || "param_out_hour_sum".equals(signCode)
                || "param_out_other".equals(signCode);
    }

    /**
     * 体温复测值（vitalsignNVal2）。
     * 中间表 signValue2 已由 HandleService 按“NVal1>=38.5 且 1 小时内复测值>=38.5”的规则计算，
     * 此处再做一次兜底校验，不满足条件一律传空串。
     */
    private String resolveRecheckValue(IntermediateTable tableInfo, String signValue) {
        if (tableInfo == null) {
            return "";
        }
        double origin;
        try {
            origin = Double.parseDouble(signValue == null ? "" : signValue.trim());
        } catch (NumberFormatException e) {
            return "";
        }
        if (origin < 38.5D) {
            return "";
        }
        String recheck = tableInfo.getSignValue2();
        if (recheck == null || recheck.trim().isEmpty()) {
            return "";
        }
        try {
            if (Double.parseDouble(recheck.trim()) < 38.5D) {
                return "";
            }
        } catch (NumberFormatException e) {
            return "";
        }
        return recheck.trim();
    }

    /**
     * 体温部位（vitalsignSVal1）：取对应时间节点 bedside.code=param_tiWenBuWei 的 strVal。
     * 优先用中间表已落库的值，历史数据没有时实时回查 bedside，取不到传空串。
     */
    private String resolveTemperatureLocation(IntermediateTable tableInfo) {
        if (tableInfo == null) {
            return "";
        }
        String location = tableInfo.getSignLocation();
        if (location != null && !location.trim().isEmpty()) {
            return location.trim();
        }
        Document doc = this.mongoDao.selectBedSideByCodeAndTimePoint(tableInfo.getPid(), "param_tiWenBuWei", tableInfo.getTimePoint());
        if (doc != null) {
            Object strVal = doc.get("strVal");
            if (strVal != null && !strVal.toString().trim().isEmpty()) {
                return strVal.toString().trim();
            }
        }
        log.info("体温部位：pid={},时间点={},未找到 param_tiWenBuWei，vitalsignSVal1 传空",
                new Object[]{tableInfo.getPid(), tableInfo.getTimePoint()});
        return "";
    }

    /**
     * recordNurseName：
     *   1. 优先取对应时间节点 bedside.code=param_Yishi 的 editUser 对应的 account.trueName；
     *   2. 没有 param_Yishi 时，取本条体征记录（如 param_T）的 editUser 对应的 account.trueName；
     *   3. 都取不到时用中间表已落库的 authorName，最终由调用方兼底 "系统管理员"。
     */
    private String resolveRecordNurseName(IntermediateTable tableInfo) {
        if (tableInfo == null) {
            return null;
        }
        Document nurseDoc = this.mongoDao.selectBedSideByCodeAndTimePoint(tableInfo.getPid(), "param_Yishi", tableInfo.getTimePoint());
        String trueName = queryTrueNameByEditUser(readEditUser(nurseDoc));
        if (trueName != null) {
            return trueName;
        }
        Document signDoc = this.mongoDao.selectBedSideByCodeAndTimePoint(tableInfo.getPid(), tableInfo.getSignCode(), tableInfo.getTimePoint());
        trueName = queryTrueNameByEditUser(readEditUser(signDoc));
        if (trueName != null) {
            return trueName;
        }
        return tableInfo.getAuthorName();
    }

    /** 读取 patient 文档字符串字段（空串视为 null） */
    private String readString(Document doc, String key) {
        if (doc == null) {
            return null;
        }
        Object value = doc.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** 读取 editUser（兼容 String 与 ObjectId 两种存储形式） */
    private String readEditUser(Document doc) {
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

    /** 根据 account._id 查 account.trueName */
    private String queryTrueNameByEditUser(String editUser) {
        if (editUser == null) {
            return null;
        }
        Document account = this.mongoDao.selectDoctorByAccountId(editUser);
        if (account == null) {
            return null;
        }
        Object trueName = account.get("trueName");
        if (trueName == null) {
            return null;
        }
        String value = trueName.toString().trim();
        return value.isEmpty() ? null : value;
    }


    public void updateUploadLog(IntermediateTable tableInfo, Boolean isSuccess, String error) {
        /* 248 */
        this.mongoDao.updateSuccessLog(tableInfo, isSuccess, error);
    }
}


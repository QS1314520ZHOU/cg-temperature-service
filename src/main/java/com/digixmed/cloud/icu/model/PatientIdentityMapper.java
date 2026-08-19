package com.digixmed.cloud.icu.model;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 患者字段映射配置
 *
 * 业务目的：集中管理MongoDB patient字段到SOAP推送字段的映射关系
 * 输入：MongoDB patient文档
 * 输出：SOAP推送所需的患者标识
 * 异常策略：字段缺失时记录WARN日志并使用默认值
 *
 * 注意：当前存在字段映射冲突，需要医院最终确认：
 * - 新需求：patientId = mrn, mrn = hisPid
 * - 旧代码：patientId = hisPid, mrn = mrn
 * - 默认使用新需求配置
 */
@Data
@Component
public class PatientIdentityMapper {

    private static final Logger log = LoggerFactory.getLogger(PatientIdentityMapper.class);

    /**
     * SOAP patientId的数据来源
     * 可选值：mrn, hisPid
     * 默认：mrn（按新需求）
     */
    @Value("${vitalsign.patient.patient-id-source:mrn}")
    private String patientIdSource;

    /**
     * SOAP mrn的数据来源
     * 可选值：hisPid, mrn
     * 默认：hisPid（按新需求）
     */
    @Value("${vitalsign.patient.mrn-source:hisPid}")
    private String mrnSource;

    /**
     * 默认series值
     * 新需求固定为"1"
     */
    @Value("${vitalsign.patient.series:1}")
    private String defaultSeries;

    /**
     * 默认wardCode值
     * 新需求为"125011"
     */
    @Value("${vitalsign.patient.ward-code:125011}")
    private String defaultWardCode;

    /**
     * 默认recordNurseId值
     * 默认"041660"（陈琳）
     */
    @Value("${vitalsign.patient.record-nurse-id:041660}")
    private String defaultRecordNurseId;

    /**
     * 从patient文档获取SOAP patientId
     *
     * @param patient MongoDB patient文档
     * @return SOAP patientId
     */
    public String getPatientId(org.bson.Document patient) {
        if (patient == null) {
            return null;
        }

        // 按需求固定：SOAP patientId = patient.mrn（配置项保留，默认 mrn），不再降级到 hisPid，防止与 mrn 字段互换
        String value = "hisPid".equals(patientIdSource)
                ? readString(patient, "hisPid")
                : readString(patient, "mrn");

        if (value == null) {
            log.warn("patient缺少{}字段，patientId为空，_id={}", patientIdSource, patient.get("_id"));
        }

        return value;
    }

    /**
     * 从patient文档获取SOAP mrn
     *
     * @param patient MongoDB patient文档
     * @return SOAP mrn
     */
    public String getMrn(org.bson.Document patient) {
        if (patient == null) {
            return null;
        }

        String value = "mrn".equals(mrnSource)
                ? readString(patient, "mrn")
                : readString(patient, "hisPid");

        if (value == null) {
            log.warn("patient缺少{}字段，mrn为空，_id={}", mrnSource, patient.get("_id"));
        }

        return value;
    }

    /**
     * 从patient文档获取患者姓名
     *
     * @param patient MongoDB patient文档
     * @return 患者姓名
     */
    public String getPatientName(org.bson.Document patient) {
        if (patient == null) {
            return null;
        }
        String name = readString(patient, "name");
        if (name == null) {
            log.warn("patient缺少name字段，patientName为空，_id={}", patient.get("_id"));
        }
        return name;
    }

    /**
     * 读取字符串字段（兼容非字符串存储，空串视为 null）
     */
    private String readString(org.bson.Document patient, String key) {
        Object value = patient.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 获取默认series
     *
     * @return series值
     */
    public String getSeries() {
        return defaultSeries;
    }

    /**
     * 获取默认wardCode
     *
     * @return wardCode值
     */
    public String getWardCode() {
        return defaultWardCode;
    }

    /**
     * 获取默认recordNurseId
     *
     * @return recordNurseId值
     */
    public String getRecordNurseId() {
        return defaultRecordNurseId;
    }
}

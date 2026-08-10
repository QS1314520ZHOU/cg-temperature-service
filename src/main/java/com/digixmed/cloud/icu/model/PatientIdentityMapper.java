package com.digixmed.cloud.icu.model;

import lombok.Data;
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
     * 新需求固定为"dba"
     */
    @Value("${vitalsign.patient.record-nurse-id:dba}")
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

        String value;
        if ("mrn".equals(patientIdSource)) {
            value = patient.getString("mrn");
        } else {
            value = patient.getString("hisPid");
        }

        if (value == null || value.isEmpty()) {
            value = patient.getString("hisPid"); // 降级到hisPid
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

        String value;
        if ("hisPid".equals(mrnSource)) {
            value = patient.getString("hisPid");
        } else {
            value = patient.getString("mrn");
        }

        if (value == null || value.isEmpty()) {
            value = patient.getString("mrn"); // 降级到mrn
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
        return patient.getString("name");
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

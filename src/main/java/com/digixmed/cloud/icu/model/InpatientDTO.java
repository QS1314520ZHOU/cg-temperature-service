package com.digixmed.cloud.icu.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在院患者DTO
 *
 * 业务目的：映射KingbaseES数据库中的在院患者信息
 * 输入：KingbaseES inpatients表
 * 输出：患者基本信息
 * 异常策略：字段缺失时记录WARN日志
 *
 * 说明：Kingbase仅用于只读查询，不使用JPA实体注解，通过原生SQL映射。
 */
@Data
public class InpatientDTO {

    /**
     * 患者ID（住院号）
     */
    private String patientId;

    /**
     * 住院号（MRN）
     */
    private String mrn;

    /**
     * 住院次数
     */
    private String series;

    /**
     * 患者姓名
     */
    private String name;

    /**
     * 科室编码
     */
    private String wardCode;

    /**
     * 患者状态
     * in: 在科
     * out: 出科
     */
    private String status;

    /**
     * 入院时间
     */
    private LocalDateTime admissionTime;

    /**
     * 入科时间
     */
    private LocalDateTime admissionWardTime;

    /**
     * 出院时间
     */
    private LocalDateTime dischargeTime;

    /**
     * 身高（cm）
     */
    private Double height;

    /**
     * 体重（kg）
     */
    private Double weight;

    /**
     * 检查患者是否在科
     *
     * @return 是否在科
     */
    public boolean isInWard() {
        return "in".equals(status);
    }
}

package com.digixmed.cloud.icu.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 统一推送模型
 *
 * 业务目的：统一所有体征类型的推送数据结构
 * 输入：各Handler处理后的数据
 * 输出：标准化的推送载荷
 * 异常策略：必填字段缺失时记录ERROR日志
 *
 * 公共默认值：
 * - series = "1"
 * - remark = ""
 * - isValid = 1
 * - wardCode = "125011"
 * - recordNurseId = "dba"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VitalSignPayload {

    /**
     * 体征名称
     * 例如：体温、脉搏、心率、呼吸、血压等
     */
    private String vitalsignName;

    /**
     * 体征类型编码
     * 例如：1001=体温, 1002=脉搏, 1003=心率, 1004=呼吸, 1005=血压
     */
    private String vitalsignType;

    /**
     * 数值1
     * 体温：体温值
     * 脉搏/心率：脉搏/心率值
     * 呼吸：呼吸频率
     * 血压：收缩压
     */
    private String vitalsignNVal1;

    /**
     * 数值2
     * 体温：复测值
     * 血压：舒张压
     */
    private String vitalsignNVal2;

    /**
     * 数值3
     * 疼痛评分：标识（1=有效）
     */
    private String vitalsignNVal3;

    /**
     * 字符串值1
     * 体温：测量部位（腋温、口温等）
     * 呼吸：呼吸机状态（使用呼吸机、停止呼吸机）
     * 大便次数：次数值
     * 出入量：总量值
     */
    private String vitalsignSVal1;

    /**
     * 字符串值2
     * 预留扩展
     */
    private String vitalsignSVal2;

    /**
     * 住院次数
     * 新需求固定为"1"
     */
    @Builder.Default
    private String series = "1";

    /**
     * 患者ID
     * 按新需求：SOAP patientId = Mongo patient.mrn
     */
    private String patientId;

    /**
     * 住院号
     * 按新需求：SOAP mrn = Mongo patient.hisPid
     */
    private String mrn;

    /**
     * 患者姓名
     */
    private String patientName;

    /**
     * 科室编码
     * 新需求为"125011"
     */
    @Builder.Default
    private String wardCode = "125011";

    /**
     * 计划时间（标准时间点）
     * 普通体征：标准时间点
     * 每日汇总：报表日07:00
     */
    private LocalDateTime planTime;

    /**
     * 记录时间
     * 普通体征：标准时间点
     * 每日汇总：报表日07:00
     */
    private LocalDateTime recordTime;

    /**
     * 记录护士ID
     * 新需求固定为"dba"
     */
    @Builder.Default
    private String recordNurseId = "dba";

    /**
     * 记录护士姓名
     * 优先从param_Yishi解析
     */
    @Builder.Default
    private String recordNurseName = "系统管理员";

    /**
     * 单位
     * 体温：℃
     * 脉搏/心率/呼吸：次/分
     * 血压：mmHg
     * 出入量：ml
     */
    private String unit;

    /**
     * 备注
     */
    @Builder.Default
    private String remark = "";

    /**
     * 是否有效
     * 1: 有效
     * 0: 无效
     */
    @Builder.Default
    private int isValid = 1;

    /**
     * 追踪ID
     * 用于日志关联和问题排查
     */
    private String traceId;

    /**
     * 患者MongoDB ID（pid）
     */
    private String mongoPid;
}

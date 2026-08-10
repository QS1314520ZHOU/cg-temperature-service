package com.digixmed.cloud.icu.pojo;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

/**
 * 体温单中间表
 *
 * 业务目的：存储待推送的体征数据，支持幂等性、重试、状态管理
 * 输入：各Handler处理后的VitalSignPayload
 * 输出：持久化的中间记录
 * 异常策略：字段缺失时记录WARN日志
 *
 * 状态流转：
 *   PENDING → SENDING → SUCCESS
 *                      → RETRY → SENDING → ...
 *                      → DEAD
 *
 * 幂等键：patientId + series + vitalsignType + planTime
 */
@Data
@Document("thermometer_intermediate")
public class IntermediateTable {

    @Id
    @ApiModelProperty(value = "主键", dataType = "String", name = "id")
    private String id;

    @ApiModelProperty(value = "幂等键", dataType = "String", name = "idempotencyKey")
    private String idempotencyKey;

    @ApiModelProperty(value = "追踪ID", dataType = "String", name = "traceId")
    private String traceId;

    @ApiModelProperty(value = "患者标识", dataType = "String", name = "patientKey")
    private String patientKey;

    @ApiModelProperty(value = "报表日期", dataType = "Date", name = "reportDate")
    private Date reportDate;

    @ApiModelProperty(value = "窗口开始时间", dataType = "Date", name = "windowStart")
    private Date windowStart;

    @ApiModelProperty(value = "窗口结束时间", dataType = "Date", name = "windowEnd")
    private Date windowEnd;

    @ApiModelProperty(value = "数据来源类型", dataType = "String", name = "sourceType")
    private String sourceType;

    @ApiModelProperty(value = "指标代码", dataType = "String", name = "metricCode")
    private String metricCode;

    @ApiModelProperty(value = "源记录ID列表", dataType = "List", name = "sourceRecordIds")
    private List<String> sourceRecordIds;

    @ApiModelProperty(value = "源值列表", dataType = "List", name = "sourceValues")
    private List<String> sourceValues;

    @ApiModelProperty(value = "推送载荷", dataType = "String", name = "payload")
    private String payload;

    @ApiModelProperty(value = "载荷哈希（幂等性）", dataType = "String", name = "payloadHash")
    private String payloadHash;

    @ApiModelProperty(value = "状态", dataType = "String", name = "status")
    private String status = "PENDING";

    @ApiModelProperty(value = "重试次数", dataType = "Integer", name = "retryCount")
    private Integer retryCount = 0;

    @ApiModelProperty(value = "下次重试时间", dataType = "Date", name = "nextRetryTime")
    private Date nextRetryTime;

    @ApiModelProperty(value = "最后错误码", dataType = "String", name = "lastErrorCode")
    private String lastErrorCode;

    @ApiModelProperty(value = "最后错误信息", dataType = "String", name = "lastErrorMessage")
    private String lastErrorMessage;

    @ApiModelProperty(value = "脱敏请求体", dataType = "String", name = "requestBodyMasked")
    private String requestBodyMasked;

    @ApiModelProperty(value = "脱敏响应体", dataType = "String", name = "responseBodyMasked")
    private String responseBodyMasked;

    @ApiModelProperty(value = "创建时间", dataType = "Date", name = "createdAt")
    private Date createdAt;

    @ApiModelProperty(value = "更新时间", dataType = "Date", name = "updatedAt")
    private Date updatedAt;

    @ApiModelProperty(value = "发送时间", dataType = "Date", name = "sentAt")
    private Date sentAt;

    // ========== 旧字段（兼容历史数据） ==========

    @ApiModelProperty(value = "记录时间点", dataType = "Date", name = "timePoint")
    private Date timePoint;

    @ApiModelProperty(value = "记录时间点（字符串）", dataType = "String", name = "timePointStr")
    private String timePointStr;

    @ApiModelProperty(value = "体征值", dataType = "String", name = "signValue")
    private String signValue;

    @ApiModelProperty(value = "体征值单位", dataType = "String", name = "signUnit")
    private String signUnit;

    @ApiModelProperty(value = "体征名称", dataType = "String", name = "signName")
    private String signName;

    @ApiModelProperty(value = "体征编码", dataType = "String", name = "signCode")
    private String signCode;

    @ApiModelProperty(value = "是否有效", dataType = "Boolean", name = "isValid")
    private Boolean isValid;

    @ApiModelProperty(value = "是否回传失败", dataType = "Boolean", name = "failed")
    private Boolean failed = false;

    @ApiModelProperty(value = "失败原因", dataType = "String", name = "errorMsg")
    private String errorMsg;

    @ApiModelProperty(value = "住院号", dataType = "String", name = "mrn")
    private String mrn;

    @ApiModelProperty(value = "住院次数", dataType = "String", name = "zycs")
    private String zycs;

    @ApiModelProperty(value = "患者名称", dataType = "String", name = "patientName")
    private String patientName;

    @ApiModelProperty(value = "病人id", dataType = "String", name = "patientId")
    private String patientId;

    @ApiModelProperty(value = "病人id（mongo）", dataType = "String", name = "pid")
    private String pid;

    @ApiModelProperty(value = "是否入科第一条记录", dataType = "Boolean", name = "isFirst")
    private Boolean isFirst;

    @ApiModelProperty(value = "体征记录人", dataType = "String", name = "authorName")
    private String authorName;

    @ApiModelProperty(value = "体征记录人id", dataType = "String", name = "authorId")
    private String authorId;

    @ApiModelProperty(value = "bedside表的id", dataType = "String", name = "bedSideId")
    private String bedSideId;

    @ApiModelProperty(value = "是否回传", dataType = "Boolean", name = "isUpload")
    private Boolean isUpload;

    @ApiModelProperty(value = "科室编码", dataType = "String", name = "deptCode")
    private String deptCode;

    @ApiModelProperty(value = "科室名称", dataType = "String", name = "deptName")
    private String deptName;

    @ApiModelProperty(value = "统计子项记录", dataType = "List", name = "childList")
    private List<Document> childList;

    @ApiModelProperty(value = "体征编码", dataType = "String", name = "vitalsignType")
    private String vitalsignType;

    @ApiModelProperty(value = "请求报文", dataType = "String", name = "requestMsg")
    private String requestMsg;

    @ApiModelProperty(value = "响应报文", dataType = "String", name = "responseMsg")
    private String responseMsg;

    @ApiModelProperty(value = "是否使用呼吸机", dataType = "Boolean", name = "inHuXiJi")
    private Boolean inHuXiJi;

    @ApiModelProperty(value = "发送时间（旧）", dataType = "Date", name = "returnTime")
    private Date returnTime;

    @ApiModelProperty(value = "最后编辑时间", dataType = "Date", name = "lastEditTime")
    private Date lastEditTime;

    // 兼容旧字段名
    public void setLastEditTime(Date lastEditTime) {
        this.lastEditTime = lastEditTime;
    }

    public void setChlidList(List<Document> chlidList) {
        this.childList = chlidList;
    }

    public List<Document> getChlidList() {
        return this.childList;
    }

    public void setReponseMsg(String responseMsg) {
        this.responseMsg = responseMsg;
    }

    public String getReponseMsg() {
        return this.responseMsg;
    }
}

/*    */
package com.digixmed.cloud.icu.pojo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@ApiModel(value = "IntermediateTable", description = "体温单回传记录")
/*    */
@Document("thermometer_intermediate")
/*    */ public class IntermediateTable {
    @Id
    /*    */
    @ApiModelProperty(value = "主键", dataType = "String", name = "id")
    /*    */ private String id;
    @ApiModelProperty(value = "记录时间点", dataType = "Date", name = "timePoint")
    /*    */ private Date timePoint;
    @ApiModelProperty(value = "记录时间点（字符串）", dataType = "String", name = "timePointStr")
    /*    */ private String timePointStr;
    @ApiModelProperty(value = "创建时间", dataType = "Date", name = "createTime")
    /*    */ private Date createTime;
    @ApiModelProperty(value = "编辑时间", dataType = "Date", name = "LastEditTime")
    /*    */ private Date LastEditTime;
    @ApiModelProperty(value = "体征值", dataType = "String", name = "signValue")
    /*    */ private String signValue;
    @ApiModelProperty(value = "体征值单位", dataType = "String", name = "signUnit")
    /*    */ private String signUnit;
    /*    */
    @ApiModelProperty(value = "体征名称", dataType = "String", name = "signName")
    /*    */ private String signName;
    /*    */
    @ApiModelProperty(value = "体征编码", dataType = "String", name = "signCode")
    /*    */ private String signCode;
    /*    */
    @ApiModelProperty(value = "是否有效", dataType = "Boolean", name = "isValid")
    /*    */ private Boolean isValid;

    /*    */
    /* 19 */
    public void setId(String id) {
        this.id = id;
    }

    public void setTimePoint(Date timePoint) {
        this.timePoint = timePoint;
    }

    public void setTimePointStr(String timePointStr) {
        this.timePointStr = timePointStr;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public void setLastEditTime(Date LastEditTime) {
        this.LastEditTime = LastEditTime;
    }

    public void setSignValue(String signValue) {
        this.signValue = signValue;
    }

    public void setSignUnit(String signUnit) {
        this.signUnit = signUnit;
    }

    public void setSignName(String signName) {
        this.signName = signName;
    }

    public void setSignCode(String signCode) {
        this.signCode = signCode;
    }

    public void setIsValid(Boolean isValid) {
        this.isValid = isValid;
    }

    public void setFailed(Boolean failed) {
        this.failed = failed;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public void setMrn(String mrn) {
        this.mrn = mrn;
    }

    public void setZycs(String zycs) {
        this.zycs = zycs;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public void setPid(String pid) {
        this.pid = pid;
    }

    public void setIsFirst(Boolean isFirst) {
        this.isFirst = isFirst;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public void setAuthorId(String authorId) {
        this.authorId = authorId;
    }

    public void setBedSideId(String bedSideId) {
        this.bedSideId = bedSideId;
    }

    public void setIsUpload(Boolean isUpload) {
        this.isUpload = isUpload;
    }

    public void setDeptCode(String deptCode) {
        this.deptCode = deptCode;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public void setChlidList(List<Document> chlidList) {
        this.chlidList = chlidList;
    }

    public void setVitalsignType(String vitalsignType) {
        this.vitalsignType = vitalsignType;
    }

    public void setRequestMsg(String requestMsg) {
        this.requestMsg = requestMsg;
    }

    public void setReponseMsg(String reponseMsg) {
        this.reponseMsg = reponseMsg;
    }

    public void setInHuXiJi(Boolean inHuXiJi) {
        this.inHuXiJi = inHuXiJi;
    }

    public void setReturnTime(Date returnTime) {
        this.returnTime = returnTime;
    }


    protected boolean canEqual(Object other) {
        return other instanceof IntermediateTable;
    }


    public String toString() {
        return "IntermediateTable(id=" + getId() + ", timePoint=" + getTimePoint() + ", timePointStr=" + getTimePointStr() + ", createTime=" + getCreateTime() + ", LastEditTime=" + getLastEditTime() + ", signValue=" + getSignValue() + ", signUnit=" + getSignUnit() + ", signName=" + getSignName() + ", signCode=" + getSignCode() + ", isValid=" + getIsValid() + ", failed=" + getFailed() + ", errorMsg=" + getErrorMsg() + ", mrn=" + getMrn() + ", zycs=" + getZycs() + ", patientName=" + getPatientName() + ", patientId=" + getPatientId() + ", pid=" + getPid() + ", isFirst=" + getIsFirst() + ", authorName=" + getAuthorName() + ", authorId=" + getAuthorId() + ", bedSideId=" + getBedSideId() + ", isUpload=" + getIsUpload() + ", deptCode=" + getDeptCode() + ", deptName=" + getDeptName() + ", chlidList=" + getChlidList() + ", vitalsignType=" + getVitalsignType() + ", requestMsg=" + getRequestMsg() + ", reponseMsg=" + getReponseMsg() + ", inHuXiJi=" + getInHuXiJi() + ", returnTime=" + getReturnTime() + ")";
    }

    public IntermediateTable(String id, Date timePoint, String timePointStr, Date createTime, Date LastEditTime, String signValue, String signUnit, String signName, String signCode, Boolean isValid, Boolean failed, String errorMsg, String mrn, String zycs, String patientName, String patientId, String pid, Boolean isFirst, String authorName, String authorId, String bedSideId, Boolean isUpload, String deptCode, String deptName, List<Document> chlidList, String vitalsignType, String requestMsg, String reponseMsg, Boolean inHuXiJi, Date returnTime) {
        /* 20 */
        this.id = id;
        this.timePoint = timePoint;
        this.timePointStr = timePointStr;
        this.createTime = createTime;
        this.LastEditTime = LastEditTime;
        this.signValue = signValue;
        this.signUnit = signUnit;
        this.signName = signName;
        this.signCode = signCode;
        this.isValid = isValid;
        this.failed = failed;
        this.errorMsg = errorMsg;
        this.mrn = mrn;
        this.zycs = zycs;
        this.patientName = patientName;
        this.patientId = patientId;
        this.pid = pid;
        this.isFirst = isFirst;
        this.authorName = authorName;
        this.authorId = authorId;
        this.bedSideId = bedSideId;
        this.isUpload = isUpload;
        this.deptCode = deptCode;
        this.deptName = deptName;
        this.chlidList = chlidList;
        this.vitalsignType = vitalsignType;
        this.requestMsg = requestMsg;
        this.reponseMsg = reponseMsg;
        this.inHuXiJi = inHuXiJi;
        this.returnTime = returnTime;
        /*    */
    }

    /*    */
    /*    */
    /*    */
    /*    */
    /*    */
    /*    */
    public String getId() {
        /* 28 */
        return this.id;
        /*    */
    }

    public Date getTimePoint() {
        /* 30 */
        return this.timePoint;
        /*    */
    }

    public String getTimePointStr() {
        /* 32 */
        return this.timePointStr;
        /*    */
    }

    public Date getCreateTime() {
        /* 34 */
        return this.createTime;
        /*    */
    }

    public Date getLastEditTime() {
        /* 36 */
        return this.LastEditTime;
        /*    */
    }

    public String getSignValue() {
        /* 38 */
        return this.signValue;
        /*    */
    }

    public String getSignUnit() {
        /* 40 */
        return this.signUnit;
        /*    */
    }

    public String getSignName() {
        /* 42 */
        return this.signName;
        /*    */
    }

    public String getSignCode() {
        /* 44 */
        return this.signCode;
        /*    */
    }

    public Boolean getIsValid() {
        /* 46 */
        return this.isValid;
        /*    */
    }

    @ApiModelProperty(value = "是否回传失败", dataType = "Boolean", name = "failed")
    /* 48 */ private Boolean failed = Boolean.valueOf(false);
    @ApiModelProperty(value = "失败原因", dataType = "Boolean", name = "errorMsg")
    private String errorMsg;
    @ApiModelProperty(value = "住院号", dataType = "String", name = "mrn")
    private String mrn;
    @ApiModelProperty(value = "住院次数", dataType = "String", name = "zycs")
    private String zycs;
    @ApiModelProperty(value = "患者名称", dataType = "String", name = "patientName")
    private String patientName;
    @ApiModelProperty(value = "病人id", dataType = "String", name = "patientId")
    private String patientId;
    @ApiModelProperty(value = "病人id（mongo）", dataType = "String", name = "patientId")
    private String pid;
    @ApiModelProperty(value = "是否入科第一条记录", dataType = "Boolean", name = "isFirst")
    private Boolean isFirst;
    @ApiModelProperty(value = "体征记录人", dataType = "String", name = "authorName")
    private String authorName;
    @ApiModelProperty(value = "体征记录人id", dataType = "String", name = "authorId")
    private String authorId;

    public Boolean getFailed() {
        return this.failed;
    }

    @ApiModelProperty(value = "bedside表的id", dataType = "String", name = "bedSideId")
    private String bedSideId;
    @ApiModelProperty(value = "是否回传", dataType = "Boolean", name = "isUpload")
    private Boolean isUpload;
    @ApiModelProperty(value = "科室编码", dataType = "String", name = "deptCode")
    private String deptCode;
    @ApiModelProperty(value = "科室名称", dataType = "String", name = "deptName")
    private String deptName;
    @ApiModelProperty(value = "统计子项记录", dataType = "List", name = "chlidList")
    private List<Document> chlidList;
    @ApiModelProperty(value = "体征编码", dataType = "String", name = "vitalsignType")
    private String vitalsignType;
    @ApiModelProperty(value = "请求报文", dataType = "String", name = "requestMsg")
    private String requestMsg;
    @ApiModelProperty(value = "响应报文", dataType = "String", name = "reponseMsg")
    private String reponseMsg;
    @ApiModelProperty(value = "是否使用呼吸机", dataType = "Boolean", name = "inHuXiJi")
    /*    */ private Boolean inHuXiJi;
    @ApiModelProperty(value = "发送时间", dataType = "Date", name = "returnTime")
    /* 50 */ private Date returnTime;

    public String getErrorMsg() {
        return this.errorMsg;
    }

    /*    */
    public String getMrn() {
        /* 52 */
        return this.mrn;
        /*    */
    }

    public String getZycs() {
        /* 54 */
        return this.zycs;
        /*    */
    }

    public String getPatientName() {
        /* 56 */
        return this.patientName;
        /*    */
    }

    public String getPatientId() {
        /* 58 */
        return this.patientId;
        /*    */
    }

    public String getPid() {
        /* 60 */
        return this.pid;
        /*    */
    }

    public Boolean getIsFirst() {
        /* 62 */
        return this.isFirst;
        /*    */
    }

    public String getAuthorName() {
        /* 64 */
        return this.authorName;
        /*    */
    }

    public String getAuthorId() {
        /* 66 */
        return this.authorId;
        /*    */
    }

    public String getBedSideId() {
        /* 68 */
        return this.bedSideId;
        /*    */
    }

    public Boolean getIsUpload() {
        /* 70 */
        return this.isUpload;
        /*    */
    }

    public String getDeptCode() {
        /* 72 */
        return this.deptCode;
        /*    */
    }

    public String getDeptName() {
        /* 74 */
        return this.deptName;
        /*    */
    }

    public List<Document> getChlidList() {
        /* 76 */
        return this.chlidList;
        /*    */
    }

    public String getVitalsignType() {
        /* 78 */
        return this.vitalsignType;
        /*    */
    }

    public String getRequestMsg() {
        /* 80 */
        return this.requestMsg;
        /*    */
    }

    public String getReponseMsg() {
        /* 82 */
        return this.reponseMsg;
        /*    */
    }

    public Boolean getInHuXiJi() {
        /* 84 */
        return this.inHuXiJi;
        /*    */
    }

    public Date getReturnTime() {
        /* 86 */
        return this.returnTime;
        /*    */
    }

    /*    */
    /*    */
    public IntermediateTable() {
    }
}


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\IntermediateTable.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
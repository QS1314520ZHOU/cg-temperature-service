package com.digixmed.cloud.icu.pojo.tubeExe;

import com.digixmed.cloud.icu.pojo.commonParam.EntityBase;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class TubeExeDto implements Serializable {
    private String id;
    private String pid;
    private String name;
    private String type;
    private String body;
    private String size;
    private String casingSize;
    private String material;
    private String urineBagType;
    private String urineLumenNum;
    private Float validDate;
    private String validDateStr;
    private String tubeLocation;
    private String cuspLocation;
    private String remark;

    /* 13 */
    public void setId(String id) {
        this.id = id;
    }

    private Date createTime;
    private Date startTime;
    private String startUser;
    private String startUesrId;
    private Date planDndTime;
    private Date endTime;
    private String endUserId;
    private String tubeDrawingReason;
    private Boolean unPlannedEndTube;
    private Boolean replace;
    private String resourceTubeExeId;
    private String replaceTubeExeId;
    private String cancelUser;
    private Date cancelTime;
    private Boolean valid;
    private EntityBase entityBase;

    public void setPid(String pid) {
        this.pid = pid;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public void setCasingSize(String casingSize) {
        this.casingSize = casingSize;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public void setUrineBagType(String urineBagType) {
        this.urineBagType = urineBagType;
    }

    public void setUrineLumenNum(String urineLumenNum) {
        this.urineLumenNum = urineLumenNum;
    }

    public void setValidDate(Float validDate) {
        this.validDate = validDate;
    }

    public void setValidDateStr(String validDateStr) {
        this.validDateStr = validDateStr;
    }

    public void setTubeLocation(String tubeLocation) {
        this.tubeLocation = tubeLocation;
    }

    public void setCuspLocation(String cuspLocation) {
        this.cuspLocation = cuspLocation;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public void setStartUser(String startUser) {
        this.startUser = startUser;
    }

    public void setStartUesrId(String startUesrId) {
        this.startUesrId = startUesrId;
    }

    public void setPlanDndTime(Date planDndTime) {
        this.planDndTime = planDndTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public void setEndUserId(String endUserId) {
        this.endUserId = endUserId;
    }

    public void setTubeDrawingReason(String tubeDrawingReason) {
        this.tubeDrawingReason = tubeDrawingReason;
    }

    public void setUnPlannedEndTube(Boolean unPlannedEndTube) {
        this.unPlannedEndTube = unPlannedEndTube;
    }

    public void setReplace(Boolean replace) {
        this.replace = replace;
    }

    public void setResourceTubeExeId(String resourceTubeExeId) {
        this.resourceTubeExeId = resourceTubeExeId;
    }

    public void setReplaceTubeExeId(String replaceTubeExeId) {
        this.replaceTubeExeId = replaceTubeExeId;
    }

    public void setCancelUser(String cancelUser) {
        this.cancelUser = cancelUser;
    }

    public void setCancelTime(Date cancelTime) {
        this.cancelTime = cancelTime;
    }

    public void setValid(Boolean valid) {
        this.valid = valid;
    }

    public void setEntityBase(EntityBase entityBase) {
        this.entityBase = entityBase;
    }

    public void setUseDays(String useDays) {
        this.useDays = useDays;
    }

    public void setCurDataHas(Boolean curDataHas) {
        this.curDataHas = curDataHas;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof TubeExeDto)) return false;
        TubeExeDto other = (TubeExeDto) o;
        if (!other.canEqual(this)) return false;
        Object this$validDate = getValidDate(), other$validDate = other.getValidDate();
        if ((this$validDate == null) ? (other$validDate != null) : !this$validDate.equals(other$validDate))
            return false;
        Object this$unPlannedEndTube = getUnPlannedEndTube(), other$unPlannedEndTube = other.getUnPlannedEndTube();
        if ((this$unPlannedEndTube == null) ? (other$unPlannedEndTube != null) : !this$unPlannedEndTube.equals(other$unPlannedEndTube))
            return false;
        Object this$replace = getReplace(), other$replace = other.getReplace();
        if ((this$replace == null) ? (other$replace != null) : !this$replace.equals(other$replace)) return false;
        Object this$valid = getValid(), other$valid = other.getValid();
        if ((this$valid == null) ? (other$valid != null) : !this$valid.equals(other$valid)) return false;
        Object this$curDataHas = getCurDataHas(), other$curDataHas = other.getCurDataHas();
        if ((this$curDataHas == null) ? (other$curDataHas != null) : !this$curDataHas.equals(other$curDataHas))
            return false;
        Object this$id = getId(), other$id = other.getId();
        if ((this$id == null) ? (other$id != null) : !this$id.equals(other$id)) return false;
        Object this$pid = getPid(), other$pid = other.getPid();
        if ((this$pid == null) ? (other$pid != null) : !this$pid.equals(other$pid)) return false;
        Object this$name = getName(), other$name = other.getName();
        if ((this$name == null) ? (other$name != null) : !this$name.equals(other$name)) return false;
        Object this$type = getType(), other$type = other.getType();
        if ((this$type == null) ? (other$type != null) : !this$type.equals(other$type)) return false;
        Object this$body = getBody(), other$body = other.getBody();
        if ((this$body == null) ? (other$body != null) : !this$body.equals(other$body)) return false;
        Object this$size = getSize(), other$size = other.getSize();
        if ((this$size == null) ? (other$size != null) : !this$size.equals(other$size)) return false;
        Object this$casingSize = getCasingSize(), other$casingSize = other.getCasingSize();
        if ((this$casingSize == null) ? (other$casingSize != null) : !this$casingSize.equals(other$casingSize))
            return false;
        Object this$material = getMaterial(), other$material = other.getMaterial();
        if ((this$material == null) ? (other$material != null) : !this$material.equals(other$material)) return false;
        Object this$urineBagType = getUrineBagType(), other$urineBagType = other.getUrineBagType();
        if ((this$urineBagType == null) ? (other$urineBagType != null) : !this$urineBagType.equals(other$urineBagType))
            return false;
        Object this$urineLumenNum = getUrineLumenNum(), other$urineLumenNum = other.getUrineLumenNum();
        if ((this$urineLumenNum == null) ? (other$urineLumenNum != null) : !this$urineLumenNum.equals(other$urineLumenNum))
            return false;
        Object this$validDateStr = getValidDateStr(), other$validDateStr = other.getValidDateStr();
        if ((this$validDateStr == null) ? (other$validDateStr != null) : !this$validDateStr.equals(other$validDateStr))
            return false;
        Object this$tubeLocation = getTubeLocation(), other$tubeLocation = other.getTubeLocation();
        if ((this$tubeLocation == null) ? (other$tubeLocation != null) : !this$tubeLocation.equals(other$tubeLocation))
            return false;
        Object this$cuspLocation = getCuspLocation(), other$cuspLocation = other.getCuspLocation();
        if ((this$cuspLocation == null) ? (other$cuspLocation != null) : !this$cuspLocation.equals(other$cuspLocation))
            return false;
        Object this$remark = getRemark(), other$remark = other.getRemark();
        if ((this$remark == null) ? (other$remark != null) : !this$remark.equals(other$remark)) return false;
        Object this$createTime = getCreateTime(), other$createTime = other.getCreateTime();
        if ((this$createTime == null) ? (other$createTime != null) : !this$createTime.equals(other$createTime))
            return false;
        Object this$startTime = getStartTime(), other$startTime = other.getStartTime();
        if ((this$startTime == null) ? (other$startTime != null) : !this$startTime.equals(other$startTime))
            return false;
        Object this$startUser = getStartUser(), other$startUser = other.getStartUser();
        if ((this$startUser == null) ? (other$startUser != null) : !this$startUser.equals(other$startUser))
            return false;
        Object this$startUesrId = getStartUesrId(), other$startUesrId = other.getStartUesrId();
        if ((this$startUesrId == null) ? (other$startUesrId != null) : !this$startUesrId.equals(other$startUesrId))
            return false;
        Object this$planDndTime = getPlanDndTime(), other$planDndTime = other.getPlanDndTime();
        if ((this$planDndTime == null) ? (other$planDndTime != null) : !this$planDndTime.equals(other$planDndTime))
            return false;
        Object this$endTime = getEndTime(), other$endTime = other.getEndTime();
        if ((this$endTime == null) ? (other$endTime != null) : !this$endTime.equals(other$endTime)) return false;
        Object this$endUserId = getEndUserId(), other$endUserId = other.getEndUserId();
        if ((this$endUserId == null) ? (other$endUserId != null) : !this$endUserId.equals(other$endUserId))
            return false;
        Object this$tubeDrawingReason = getTubeDrawingReason(), other$tubeDrawingReason = other.getTubeDrawingReason();
        if ((this$tubeDrawingReason == null) ? (other$tubeDrawingReason != null) : !this$tubeDrawingReason.equals(other$tubeDrawingReason))
            return false;
        Object this$resourceTubeExeId = getResourceTubeExeId(), other$resourceTubeExeId = other.getResourceTubeExeId();
        if ((this$resourceTubeExeId == null) ? (other$resourceTubeExeId != null) : !this$resourceTubeExeId.equals(other$resourceTubeExeId))
            return false;
        Object this$replaceTubeExeId = getReplaceTubeExeId(), other$replaceTubeExeId = other.getReplaceTubeExeId();
        if ((this$replaceTubeExeId == null) ? (other$replaceTubeExeId != null) : !this$replaceTubeExeId.equals(other$replaceTubeExeId))
            return false;
        Object this$cancelUser = getCancelUser(), other$cancelUser = other.getCancelUser();
        if ((this$cancelUser == null) ? (other$cancelUser != null) : !this$cancelUser.equals(other$cancelUser))
            return false;
        Object this$cancelTime = getCancelTime(), other$cancelTime = other.getCancelTime();
        if ((this$cancelTime == null) ? (other$cancelTime != null) : !this$cancelTime.equals(other$cancelTime))
            return false;
        Object this$entityBase = getEntityBase(), other$entityBase = other.getEntityBase();
        if ((this$entityBase == null) ? (other$entityBase != null) : !this$entityBase.equals(other$entityBase))
            return false;
        Object this$useDays = getUseDays(), other$useDays = other.getUseDays();
        if ((this$useDays == null) ? (other$useDays != null) : !this$useDays.equals(other$useDays)) return false;
        Object this$accountName = getAccountName(), other$accountName = other.getAccountName();
        return !((this$accountName == null) ? (other$accountName != null) : !this$accountName.equals(other$accountName));
    }

    protected boolean canEqual(Object other) {
        return other instanceof TubeExeDto;
    }


    public String toString() {
        return "TubeExeDto(id=" + getId() + ", pid=" + getPid() + ", name=" + getName() + ", type=" + getType() + ", body=" + getBody() + ", size=" + getSize() + ", casingSize=" + getCasingSize() + ", material=" + getMaterial() + ", urineBagType=" + getUrineBagType() + ", urineLumenNum=" + getUrineLumenNum() + ", validDate=" + getValidDate() + ", validDateStr=" + getValidDateStr() + ", tubeLocation=" + getTubeLocation() + ", cuspLocation=" + getCuspLocation() + ", remark=" + getRemark() + ", createTime=" + getCreateTime() + ", startTime=" + getStartTime() + ", startUser=" + getStartUser() + ", startUesrId=" + getStartUesrId() + ", planDndTime=" + getPlanDndTime() + ", endTime=" + getEndTime() + ", endUserId=" + getEndUserId() + ", tubeDrawingReason=" + getTubeDrawingReason() + ", unPlannedEndTube=" + getUnPlannedEndTube() + ", replace=" + getReplace() + ", resourceTubeExeId=" + getResourceTubeExeId() + ", replaceTubeExeId=" + getReplaceTubeExeId() + ", cancelUser=" + getCancelUser() + ", cancelTime=" + getCancelTime() + ", valid=" + getValid() + ", entityBase=" + getEntityBase() + ", useDays=" + getUseDays() + ", curDataHas=" + getCurDataHas() + ", accountName=" + getAccountName() + ")";
    }

    public TubeExeDto() {
    }

    /* 16 */
    public String getId() {
        return this.id;
    }

    public String getPid() {
        /* 17 */
        return this.pid;
    }

    /* 19 */
    public String getName() {
        return this.name;
    }

    /* 20 */
    public String getType() {
        return this.type;
    }

    /* 21 */
    public String getBody() {
        return this.body;
    }

    /* 22 */
    public String getSize() {
        return this.size;
    }

    /* 23 */
    public String getCasingSize() {
        return this.casingSize;
    }

    /* 24 */
    public String getMaterial() {
        return this.material;
    }

    /* 25 */
    public String getUrineBagType() {
        return this.urineBagType;
    }

    /* 26 */
    public String getUrineLumenNum() {
        return this.urineLumenNum;
    }

    /* 27 */
    public Float getValidDate() {
        return this.validDate;
    }

    /* 28 */
    public String getValidDateStr() {
        return this.validDateStr;
    }

    /* 29 */
    public String getTubeLocation() {
        return this.tubeLocation;
    }

    /* 30 */
    public String getCuspLocation() {
        return this.cuspLocation;
    }

    public String getRemark() {
        /* 31 */
        return this.remark;
    }

    /* 33 */
    public Date getCreateTime() {
        return this.createTime;
    }

    /* 34 */
    public Date getStartTime() {
        return this.startTime;
    }

    /* 35 */
    public String getStartUser() {
        return this.startUser;
    }

    /* 36 */
    public String getStartUesrId() {
        return this.startUesrId;
    }

    /* 37 */
    public Date getPlanDndTime() {
        return this.planDndTime;
    }

    /* 38 */
    public Date getEndTime() {
        return this.endTime;
    }

    /* 39 */
    public String getEndUserId() {
        return this.endUserId;
    }

    /* 40 */
    public String getTubeDrawingReason() {
        return this.tubeDrawingReason;
    }

    /* 41 */
    public Boolean getUnPlannedEndTube() {
        return this.unPlannedEndTube;
    }

    public Boolean getReplace() {
        /* 42 */
        return this.replace;
    }

    public String getResourceTubeExeId() {
        /* 46 */
        return this.resourceTubeExeId;
    }

    public String getReplaceTubeExeId() {
        /* 50 */
        return this.replaceTubeExeId;
        /* 51 */
    }

    public String getCancelUser() {
        return this.cancelUser;
    }

    /* 52 */
    public Date getCancelTime() {
        return this.cancelTime;
    }

    /* 53 */
    public Boolean getValid() {
        return this.valid;
    }

    public EntityBase getEntityBase() {
        /* 54 */
        return this.entityBase;
    }


    /* 58 */   public static Map<String, String> tubeInfoNameMapField = new HashMap<>();
    private String useDays;
    private Boolean curDataHas;
    private String accountName;

    /* 61 */
    public String getUseDays() {
        return this.useDays;
    }

    public Boolean getCurDataHas() {
        /* 62 */
        return this.curDataHas;
    }

    public String getAccountName() {
        /* 66 */
        return this.accountName;
    }


    public TubeExeDto(String id, String pid, String name, String type, Date startTime, Date planDndTime, Date endTime, Boolean unPlannedEndTube, Boolean replace, String resourceTubeExeId, String replaceTubeExeId, Boolean valid) {
        /* 73 */
        this.id = id;
        /* 74 */
        this.pid = pid;
        /* 75 */
        this.name = name;
        /* 76 */
        this.type = type;
        /* 77 */
        this.startTime = startTime;
        /* 78 */
        this.planDndTime = planDndTime;
        /* 79 */
        this.endTime = endTime;
        /* 80 */
        this.unPlannedEndTube = unPlannedEndTube;
        /* 81 */
        this.replace = replace;
        /* 82 */
        this.resourceTubeExeId = resourceTubeExeId;
        /* 83 */
        this.replaceTubeExeId = replaceTubeExeId;
        /* 84 */
        this.valid = valid;
    }


    static {
        /* 89 */
        tubeInfoNameMapField.put("插管部位", "body");
        /* 90 */
        tubeInfoNameMapField.put("型号", "size");
        /* 91 */
        tubeInfoNameMapField.put("套管型号", "casingSize");
        /* 92 */
        tubeInfoNameMapField.put("材质", "material");
        /* 93 */
        tubeInfoNameMapField.put("尿袋类型", "urineBagType");
        /* 94 */
        tubeInfoNameMapField.put("尿管腔数目", "urineLumenNum");
        /* 95 */
        tubeInfoNameMapField.put("有效期", "validDate");
        /* 96 */
        tubeInfoNameMapField.put("插管地点", "tubeLocation");
        /* 97 */
        tubeInfoNameMapField.put("尖端位置", "cuspLocation");
        /* 98 */
        tubeInfoNameMapField.put("备注", "remark");
    }
}


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\tubeExe\TubeExeDto.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
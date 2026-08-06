package com.digixmed.cloud.icu.pojo.configParam;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("configInOutVolume")
public class ConfigInOutVolume {
    @Id
    private String id;
    private String deptCode;

    /* 11 */
    public void setId(String id) {
        this.id = id;
    }

    public void setDeptCode(String deptCode) {
        this.deptCode = deptCode;
    }

    public void setShowTimeAddBtn(Boolean showTimeAddBtn) {
        this.showTimeAddBtn = showTimeAddBtn;
    }

    public void setEnableTubeRemark(Boolean enableTubeRemark) {
        this.enableTubeRemark = enableTubeRemark;
    }

    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof ConfigInOutVolume)) return false;
        ConfigInOutVolume other = (ConfigInOutVolume) o;
        if (!other.canEqual(this)) return false;
        Object this$showTimeAddBtn = getShowTimeAddBtn(), other$showTimeAddBtn = other.getShowTimeAddBtn();
        if ((this$showTimeAddBtn == null) ? (other$showTimeAddBtn != null) : !this$showTimeAddBtn.equals(other$showTimeAddBtn))
            return false;
        Object this$enableTubeRemark = getEnableTubeRemark(), other$enableTubeRemark = other.getEnableTubeRemark();
        if ((this$enableTubeRemark == null) ? (other$enableTubeRemark != null) : !this$enableTubeRemark.equals(other$enableTubeRemark))
            return false;
        Object this$id = getId(), other$id = other.getId();
        if ((this$id == null) ? (other$id != null) : !this$id.equals(other$id)) return false;
        Object this$deptCode = getDeptCode(), other$deptCode = other.getDeptCode();
        return !((this$deptCode == null) ? (other$deptCode != null) : !this$deptCode.equals(other$deptCode));
    }

    protected boolean canEqual(Object other) {
        return other instanceof ConfigInOutVolume;
    }


    public String toString() {
        return "ConfigInOutVolume(id=" + getId() + ", deptCode=" + getDeptCode() + ", showTimeAddBtn=" + getShowTimeAddBtn() + ", enableTubeRemark=" + getEnableTubeRemark() + ")";
    }


    public String getId() {
        /* 16 */
        return this.id;
    }

    public String getDeptCode() {
        /* 17 */
        return this.deptCode;
        /* 18 */
    }

    private Boolean showTimeAddBtn = Boolean.valueOf(false);

    public Boolean getShowTimeAddBtn() {
        return this.showTimeAddBtn;
    }

    /* 19 */    private Boolean enableTubeRemark = Boolean.valueOf(false);

    public Boolean getEnableTubeRemark() {
        return this.enableTubeRemark;
    }

}


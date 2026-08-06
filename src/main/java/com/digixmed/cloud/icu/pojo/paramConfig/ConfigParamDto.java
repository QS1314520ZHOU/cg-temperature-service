package com.digixmed.cloud.icu.pojo.paramConfig;

import com.digixmed.cloud.icu.pojo.commonParam.EntityBase;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ConfigParamDto implements Serializable {
    protected String id;
    protected String code;
    protected String name;
    private String enName;
    private String desc;

    /* 12 */
    public void setId(String id) {
        this.id = id;
    }

    private String dataType;
    private Boolean multipleChoice;
    private Integer floatCount;
    private String src;
    private String calculation;

    public void setCode(String code) {
        this.code = code;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setEnName(String enName) {
        this.enName = enName;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public void setMultipleChoice(Boolean multipleChoice) {
        this.multipleChoice = multipleChoice;
    }

    public void setFloatCount(Integer floatCount) {
        this.floatCount = floatCount;
    }

    public void setSrc(String src) {
        this.src = src;
    }

    public void setCalculation(String calculation) {
        this.calculation = calculation;
    }

    public void setParams(List<String> params) {
        this.params = params;
    }

    public void setConfigItemList(List<ConfigItemDto> configItemList) {
        this.configItemList = configItemList;
    }

    public void setUnitCode(String unitCode) {
        this.unitCode = unitCode;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public void setCreateUser(String createUser) {
        this.createUser = createUser;
    }

    public void setValidMax(String validMax) {
        this.validMax = validMax;
    }

    public void setValidMin(String validMin) {
        this.validMin = validMin;
    }

    public void setEntityBase(EntityBase entityBase) {
        this.entityBase = entityBase;
    }

    public void setRemark(boolean remark) {
        this.remark = remark;
    }

    public void setDeptCode(String deptCode) {
        this.deptCode = deptCode;
    }

    public void setValid(Boolean valid) {
        this.valid = valid;
    }

    /* 14 */
    public String getId() {
        return this.id;
    }

    /* 15 */
    public String getCode() {
        return this.code;
    }

    /* 16 */
    public String getName() {
        return this.name;
    }

    /* 17 */
    public String getEnName() {
        return this.enName;
    }

    /* 18 */
    public String getDesc() {
        return this.desc;
    }

    /* 19 */
    public String getDataType() {
        return this.dataType;
    }

    /* 20 */
    public Boolean getMultipleChoice() {
        return this.multipleChoice;
    }

    /* 21 */
    public Integer getFloatCount() {
        return this.floatCount;
    }

    /* 22 */
    public String getSrc() {
        return this.src;
    }

    public String getCalculation() {
        /* 23 */
        return this.calculation;
        /* 24 */
    }

    private List<String> params = new ArrayList<>();

    public List<String> getParams() {
        return this.params;
    }

    /* 25 */    private List<ConfigItemDto> configItemList = new ArrayList<>();
    private String unitCode;
    private String unit;
    private Date createTime;
    private String createUser;
    private String validMax;
    private String validMin;
    private EntityBase entityBase;

    public List<ConfigItemDto> getConfigItemList() {
        return this.configItemList;
    }

    /* 26 */
    public String getUnitCode() {
        return this.unitCode;
    }

    /* 27 */
    public String getUnit() {
        return this.unit;
    }

    /* 28 */
    public Date getCreateTime() {
        return this.createTime;
    }

    /* 29 */
    public String getCreateUser() {
        return this.createUser;
    }

    /* 30 */
    public String getValidMax() {
        return this.validMax;
    }

    /* 31 */
    public String getValidMin() {
        return this.validMin;
    }

    /* 32 */
    public EntityBase getEntityBase() {
        return this.entityBase;
    }

    private boolean remark = false;
    private String deptCode;

    /* 33 */
    public boolean isRemark() {
        return this.remark;
    }

    public String getDeptCode() {
        /* 34 */
        return this.deptCode;
        /* 35 */
    }

    private Boolean valid = Boolean.valueOf(true);

    public Boolean getValid() {
        return this.valid;
    }


    public String toString() {
        /* 39 */
        return getCode() + ":" + getCode();
    }


    public int hashCode() {
        /* 44 */
        return HashCodeBuilder.reflectionHashCode(this);
    }
}

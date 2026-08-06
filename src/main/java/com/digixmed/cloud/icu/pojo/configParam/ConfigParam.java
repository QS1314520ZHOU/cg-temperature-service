 package com.digixmed.cloud.icu.pojo.configParam;

import cn.hutool.core.builder.HashCodeBuilder;
import com.digixmed.cloud.icu.pojo.commonParam.EntityBase;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

 @Document("configParam")
 public class ConfigParam { protected String id; @Indexed(direction = IndexDirection.DESCENDING, unique = true)
   protected String code;
   protected String name;
   private String enName;
   private String desc;
   private String dataType;
   private Boolean multipleChoice;
   private Integer floatCount;
   
  public void setId(String id) { this.id = id; } private String src; private String calculation; private List<String> params; private List<ConfigItem> configItemList; private String unitCode; private String unit; private String validMax; private String validMin; private EntityBase entityBase; public void setCode(String code) { this.code = code; } public void setName(String name) { this.name = name; } public void setEnName(String enName) { this.enName = enName; } public void setDesc(String desc) { this.desc = desc; } public void setDataType(String dataType) { this.dataType = dataType; } public void setMultipleChoice(Boolean multipleChoice) { this.multipleChoice = multipleChoice; } public void setFloatCount(Integer floatCount) { this.floatCount = floatCount; } public void setSrc(String src) { this.src = src; } public void setCalculation(String calculation) { this.calculation = calculation; } public void setParams(List<String> params) { this.params = params; } public void setConfigItemList(List<ConfigItem> configItemList) { this.configItemList = configItemList; } public void setUnitCode(String unitCode) { this.unitCode = unitCode; } public void setUnit(String unit) { this.unit = unit; } public void setValidMax(String validMax) { this.validMax = validMax; } public void setValidMin(String validMin) { this.validMin = validMin; } public void setEntityBase(EntityBase entityBase) { this.entityBase = entityBase; } public void setRemark(boolean remark) { this.remark = remark; } public void setDeptCode(String deptCode) { this.deptCode = deptCode; } public void setValid(Boolean valid) { this.valid = valid; }
   
   public String getId() {
    return this.id;
   }
   public String getCode() { return this.code; }
  public String getName() { return this.name; }
   public String getEnName() { return this.enName; }
  public String getDesc() { return this.desc; }
   public String getDataType() { return this.dataType; }
   public Boolean getMultipleChoice() { return this.multipleChoice; }
  public Integer getFloatCount() { return this.floatCount; }
   public String getSrc() { return this.src; }
   public String getCalculation() { return this.calculation; }
  public List<String> getParams() { return this.params; }
   public List<ConfigItem> getConfigItemList() { return this.configItemList; }
   public String getUnitCode() { return this.unitCode; }
   public String getUnit() { return this.unit; }
   public String getValidMax() { return this.validMax; }
   public String getValidMin() { return this.validMin; }
  public EntityBase getEntityBase() { return this.entityBase; } private boolean remark = false; private String deptCode;
  public boolean isRemark() { return this.remark; } public String getDeptCode() {
     return this.deptCode;
   } private Boolean valid = Boolean.valueOf(true); public Boolean getValid() { return this.valid; }
 
   
   public String toString() {
    return getCode() + ":" + getCode();
   }
 
   
   public int hashCode() {
     return HashCodeBuilder.reflectionHashCode(this);
   } }



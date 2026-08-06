/*    */ package com.digixmed.cloud.icu.pojo.commonParam;
/*    */ 
/*    */ import java.io.Serializable;
/*    */ import java.util.ArrayList;
/*    */ import java.util.List;
/*    */ import java.util.Objects;
/*    */ 
/*    */ public class Item implements Serializable {
/*    */   private String code;
/*    */   private String name;
/*    */   private String unit;
/*    */   private Float validMin;
/*    */   private Float validMax;
/*    */   private String calculation;
/*    */   
/* 16 */   public void setCode(String code) { this.code = code; } public void setName(String name) { this.name = name; } public void setUnit(String unit) { this.unit = unit; } public void setValidMin(Float validMin) { this.validMin = validMin; } public void setValidMax(Float validMax) { this.validMax = validMax; } public void setCalculation(String calculation) { this.calculation = calculation; } public void setParams(List<String> params) { this.params = params; } public void setConfigItemList(List<ConfigItemDto> configItemList) { this.configItemList = configItemList; } public void setFloatCount(Integer floatCount) { this.floatCount = floatCount; } public void setMultipleChoice(Boolean multipleChoice) { this.multipleChoice = multipleChoice; } public void setDataType(String dataType) { this.dataType = dataType; } public void setSrc(String src) { this.src = src; } public void setEnName(String enName) { this.enName = enName; } public void setRemark(boolean remark) { this.remark = remark; } public void setDeleteRecord(DeleteRecord deleteRecord) { this.deleteRecord = deleteRecord; } public String toString() { return "Item(code=" + getCode() + ", name=" + getName() + ", unit=" + getUnit() + ", validMin=" + getValidMin() + ", validMax=" + getValidMax() + ", calculation=" + getCalculation() + ", params=" + getParams() + ", configItemList=" + getConfigItemList() + ", floatCount=" + getFloatCount() + ", multipleChoice=" + getMultipleChoice() + ", dataType=" + getDataType() + ", src=" + getSrc() + ", enName=" + getEnName() + ", remark=" + isRemark() + ", deleteRecord=" + getDeleteRecord() + ")"; } public Item(String code, String name, String unit, Float validMin, Float validMax, String calculation, List<String> params, List<ConfigItemDto> configItemList, Integer floatCount, Boolean multipleChoice, String dataType, String src, String enName, boolean remark, DeleteRecord deleteRecord) {
/* 17 */     this.code = code; this.name = name; this.unit = unit; this.validMin = validMin; this.validMax = validMax; this.calculation = calculation; this.params = params; this.configItemList = configItemList; this.floatCount = floatCount; this.multipleChoice = multipleChoice; this.dataType = dataType; this.src = src; this.enName = enName; this.remark = remark; this.deleteRecord = deleteRecord;
/*    */   }
/*    */   
/*    */   public String getCode() {
/* 21 */     return this.code;
/* 22 */   } public String getName() { return this.name; }
/* 23 */   public String getUnit() { return this.unit; }
/* 24 */   public Float getValidMin() { return this.validMin; }
/* 25 */   public Float getValidMax() { return this.validMax; } public String getCalculation() {
/* 26 */     return this.calculation;
/* 27 */   } private List<String> params = new ArrayList<>(); public List<String> getParams() { return this.params; }
/* 28 */    private Integer floatCount; private Boolean multipleChoice; private String dataType; private List<ConfigItemDto> configItemList = new ArrayList<>(); private String src; private String enName; public List<ConfigItemDto> getConfigItemList() { return this.configItemList; }
/* 29 */   public Integer getFloatCount() { return this.floatCount; }
/* 30 */   public Boolean getMultipleChoice() { return this.multipleChoice; }
/* 31 */   public String getDataType() { return this.dataType; }
/* 32 */   public String getSrc() { return this.src; }
/* 33 */   public String getEnName() { return this.enName; } private boolean remark = false; private DeleteRecord deleteRecord;
/* 34 */   public boolean isRemark() { return this.remark; } public DeleteRecord getDeleteRecord() {
/* 35 */     return this.deleteRecord;
/*    */   }
/*    */   public Item(String code) {
/* 38 */     this.code = code;
/*    */   }
/*    */   
/*    */   public Item(String code, String name) {
/* 42 */     this.code = code;
/* 43 */     this.name = name;
/*    */   }
/*    */   
/*    */   public Item(String code, String name, String unit, String calculation, Integer floatCount, String dataType, String enName) {
/* 47 */     this.code = code;
/* 48 */     this.name = name;
/* 49 */     this.unit = unit;
/* 50 */     this.calculation = calculation;
/* 51 */     this.floatCount = floatCount;
/* 52 */     this.dataType = dataType;
/* 53 */     this.enName = enName;
/*    */   }
/*    */ 
/*    */   
/*    */   public boolean equals(Object o) {
/* 58 */     if (this == o) return true; 
/* 59 */     if (o == null || getClass() != o.getClass()) return false; 
/* 60 */     Item item = (Item)o;
/* 61 */     return this.code.equals(item.code);
/*    */   }
/*    */ 
/*    */   
/*    */   public int hashCode() {
/* 66 */     return Objects.hash(new Object[] { this.code });
/*    */   }
/*    */   
/*    */   public Item() {}
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\commonParam\Item.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
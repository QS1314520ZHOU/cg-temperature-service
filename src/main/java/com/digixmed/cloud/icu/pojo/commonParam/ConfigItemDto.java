/*    */ package com.digixmed.cloud.icu.pojo.commonParam;
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ public class ConfigItemDto
/*    */ {
/*    */   private String value;
/*    */   private String showStr;
/*    */   
/*    */   public void setValue(String value) {
/* 12 */     this.value = value; } public void setShowStr(String showStr) { this.showStr = showStr; } public boolean equals(Object o) { if (o == this) return true;  if (!(o instanceof ConfigItemDto)) return false;  ConfigItemDto other = (ConfigItemDto)o; if (!other.canEqual(this)) return false;  Object this$value = getValue(), other$value = other.getValue(); if ((this$value == null) ? (other$value != null) : !this$value.equals(other$value)) return false;  Object this$showStr = getShowStr(), other$showStr = other.getShowStr(); return !((this$showStr == null) ? (other$showStr != null) : !this$showStr.equals(other$showStr)); } protected boolean canEqual(Object other) { return other instanceof ConfigItemDto; } public int hashCode() { int PRIME = 59; int result = 1; Object $value = getValue(); result = result * 59 + (($value == null) ? 43 : $value.hashCode()); Object $showStr = getShowStr(); return result * 59 + (($showStr == null) ? 43 : $showStr.hashCode()); } public String toString() { return "ConfigItemDto(value=" + getValue() + ", showStr=" + getShowStr() + ")"; }
/*    */ 
/*    */   
/*    */   public ConfigItemDto() {}
/* 16 */   public String getValue() { return this.value; } public String getShowStr() {
/* 17 */     return this.showStr;
/*    */   }
/*    */   public ConfigItemDto(String value, String showStr) {
/* 20 */     this.value = value;
/* 21 */     this.showStr = showStr;
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\commonParam\ConfigItemDto.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
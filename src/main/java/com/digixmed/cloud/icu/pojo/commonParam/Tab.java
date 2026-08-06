/*    */ package com.digixmed.cloud.icu.pojo.commonParam;
/*    */ 
/*    */ import java.util.List;
/*    */ 
/*    */ 
/*    */ public class Tab
/*    */ {
/*    */   private String name;
/*    */   private List<Group> groups;
/*    */   
/*    */   public void setName(String name) {
/* 12 */     this.name = name; } public void setGroups(List<Group> groups) { this.groups = groups; } public boolean equals(Object o) { if (o == this) return true;  if (!(o instanceof Tab)) return false;  Tab other = (Tab)o; if (!other.canEqual(this)) return false;  Object this$name = getName(), other$name = other.getName(); if ((this$name == null) ? (other$name != null) : !this$name.equals(other$name)) return false;  Object<Group> this$groups = (Object<Group>)getGroups(), other$groups = (Object<Group>)other.getGroups(); return !((this$groups == null) ? (other$groups != null) : !this$groups.equals(other$groups)); } protected boolean canEqual(Object other) { return other instanceof Tab; } public int hashCode() { int PRIME = 59; result = 1; Object $name = getName(); result = result * 59 + (($name == null) ? 43 : $name.hashCode()); Object<Group> $groups = (Object<Group>)getGroups(); return result * 59 + (($groups == null) ? 43 : $groups.hashCode()); } public String toString() { return "Tab(name=" + getName() + ", groups=" + getGroups() + ")"; } public Tab(String name, List<Group> groups) {
/* 13 */     this.name = name; this.groups = groups;
/*    */   }
/* 15 */   public String getName() { return this.name; } public List<Group> getGroups() {
/* 16 */     return this.groups;
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\commonParam\Tab.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
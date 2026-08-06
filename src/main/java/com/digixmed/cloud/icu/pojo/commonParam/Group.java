/*    */ package com.digixmed.cloud.icu.pojo.commonParam;
/*    */ 
/*    */ import java.util.List;
/*    */ 
/*    */ 
/*    */ 
/*    */ public class Group
/*    */ {
/*    */   private String name;
/*    */   private List<Item> items;
/*    */   
/*    */   public void setName(String name) {
/* 13 */     this.name = name; } public void setItems(List<Item> items) { this.items = items; } public boolean equals(Object o) { if (o == this) return true;  if (!(o instanceof Group)) return false;  Group other = (Group)o; if (!other.canEqual(this)) return false;  Object this$name = getName(), other$name = other.getName(); if ((this$name == null) ? (other$name != null) : !this$name.equals(other$name)) return false;  Object<Item> this$items = (Object<Item>)getItems(), other$items = (Object<Item>)other.getItems(); return !((this$items == null) ? (other$items != null) : !this$items.equals(other$items)); } protected boolean canEqual(Object other) { return other instanceof Group; } public int hashCode() { int PRIME = 59; result = 1; Object $name = getName(); result = result * 59 + (($name == null) ? 43 : $name.hashCode()); Object<Item> $items = (Object<Item>)getItems(); return result * 59 + (($items == null) ? 43 : $items.hashCode()); } public String toString() { return "Group(name=" + getName() + ", items=" + getItems() + ")"; }
/*    */    public Group() {} public Group(String name, List<Item> items) {
/* 15 */     this.name = name; this.items = items;
/*    */   }
/* 17 */   public String getName() { return this.name; } public List<Item> getItems() {
/* 18 */     return this.items;
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\commonParam\Group.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
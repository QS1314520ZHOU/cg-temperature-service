/*    */ package com.digixmed.cloud.icu.pojo.commonParam;
/*    */ 
/*    */ import org.springframework.data.mongodb.core.mapping.Document;
/*    */ 
/*    */ @Document("configUnit")
/*    */ public class ConfigUnit {
/*    */   @Id
/*    */   private String id;
/*    */   private String valid;
/*    */   @Indexed
/*    */   private String code;
/*    */   
/* 13 */   public void setId(String id) { this.id = id; } private String name; private String group; private String sort; private EntityBase entityBase; public void setValid(String valid) { this.valid = valid; } public void setCode(String code) { this.code = code; } public void setName(String name) { this.name = name; } public void setGroup(String group) { this.group = group; } public void setSort(String sort) { this.sort = sort; } public void setEntityBase(EntityBase entityBase) { this.entityBase = entityBase; } public boolean equals(Object o) { if (o == this) return true;  if (!(o instanceof ConfigUnit)) return false;  ConfigUnit other = (ConfigUnit)o; if (!other.canEqual(this)) return false;  Object this$id = getId(), other$id = other.getId(); if ((this$id == null) ? (other$id != null) : !this$id.equals(other$id)) return false;  Object this$valid = getValid(), other$valid = other.getValid(); if ((this$valid == null) ? (other$valid != null) : !this$valid.equals(other$valid)) return false;  Object this$code = getCode(), other$code = other.getCode(); if ((this$code == null) ? (other$code != null) : !this$code.equals(other$code)) return false;  Object this$name = getName(), other$name = other.getName(); if ((this$name == null) ? (other$name != null) : !this$name.equals(other$name)) return false;  Object this$group = getGroup(), other$group = other.getGroup(); if ((this$group == null) ? (other$group != null) : !this$group.equals(other$group)) return false;  Object this$sort = getSort(), other$sort = other.getSort(); if ((this$sort == null) ? (other$sort != null) : !this$sort.equals(other$sort)) return false;  Object this$entityBase = getEntityBase(), other$entityBase = other.getEntityBase(); return !((this$entityBase == null) ? (other$entityBase != null) : !this$entityBase.equals(other$entityBase)); } protected boolean canEqual(Object other) { return other instanceof ConfigUnit; } public int hashCode() { int PRIME = 59; result = 1; Object $id = getId(); result = result * 59 + (($id == null) ? 43 : $id.hashCode()); Object $valid = getValid(); result = result * 59 + (($valid == null) ? 43 : $valid.hashCode()); Object $code = getCode(); result = result * 59 + (($code == null) ? 43 : $code.hashCode()); Object $name = getName(); result = result * 59 + (($name == null) ? 43 : $name.hashCode()); Object $group = getGroup(); result = result * 59 + (($group == null) ? 43 : $group.hashCode()); Object $sort = getSort(); result = result * 59 + (($sort == null) ? 43 : $sort.hashCode()); Object $entityBase = getEntityBase(); return result * 59 + (($entityBase == null) ? 43 : $entityBase.hashCode()); } public String toString() { return "ConfigUnit(id=" + getId() + ", valid=" + getValid() + ", code=" + getCode() + ", name=" + getName() + ", group=" + getGroup() + ", sort=" + getSort() + ", entityBase=" + getEntityBase() + ")"; }
/*    */ 
/*    */   
/* 16 */   public String getId() { return this.id; } public String getValid() {
/* 17 */     return this.valid;
/*    */   }
/* 19 */   public String getCode() { return this.code; }
/* 20 */   public String getName() { return this.name; }
/* 21 */   public String getGroup() { return this.group; }
/* 22 */   public String getSort() { return this.sort; } public EntityBase getEntityBase() {
/* 23 */     return this.entityBase;
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\commonParam\ConfigUnit.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
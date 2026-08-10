package com.digixmed.cloud.icu.pojo.commonParam;

import java.util.Date;

public class EntityBase {
/*    */   private String createUserId;
/*    */   private String createUser;
/*    */   private Date createTime;
/*    */   private String lastEditId;
/*    */   private String lastEditUser;
/*    */   private Date lastEditTime;
/*    */   
/* 11 */   public void setCreateUserId(String createUserId) { this.createUserId = createUserId; } public void setCreateUser(String createUser) { this.createUser = createUser; } public void setCreateTime(Date createTime) { this.createTime = createTime; } public void setLastEditId(String lastEditId) { this.lastEditId = lastEditId; } public void setLastEditUser(String lastEditUser) { this.lastEditUser = lastEditUser; } public void setLastEditTime(Date lastEditTime) { this.lastEditTime = lastEditTime; } public boolean equals(Object o) { if (o == this) return true;  if (!(o instanceof EntityBase)) return false;  EntityBase other = (EntityBase)o; if (!other.canEqual(this)) return false;  Object this$createUserId = getCreateUserId(), other$createUserId = other.getCreateUserId(); if ((this$createUserId == null) ? (other$createUserId != null) : !this$createUserId.equals(other$createUserId)) return false;  Object this$createUser = getCreateUser(), other$createUser = other.getCreateUser(); if ((this$createUser == null) ? (other$createUser != null) : !this$createUser.equals(other$createUser)) return false;  Object this$createTime = getCreateTime(), other$createTime = other.getCreateTime(); if ((this$createTime == null) ? (other$createTime != null) : !this$createTime.equals(other$createTime)) return false;  Object this$lastEditId = getLastEditId(), other$lastEditId = other.getLastEditId(); if ((this$lastEditId == null) ? (other$lastEditId != null) : !this$lastEditId.equals(other$lastEditId)) return false;  Object this$lastEditUser = getLastEditUser(), other$lastEditUser = other.getLastEditUser(); if ((this$lastEditUser == null) ? (other$lastEditUser != null) : !this$lastEditUser.equals(other$lastEditUser)) return false;  Object this$lastEditTime = getLastEditTime(), other$lastEditTime = other.getLastEditTime(); return !((this$lastEditTime == null) ? (other$lastEditTime != null) : !this$lastEditTime.equals(other$lastEditTime)); } protected boolean canEqual(Object other) { return other instanceof EntityBase; } public int hashCode() { int PRIME = 59; int result = 1; Object $createUserId = getCreateUserId(); result = result * 59 + (($createUserId == null) ? 43 : $createUserId.hashCode()); Object $createUser = getCreateUser(); result = result * 59 + (($createUser == null) ? 43 : $createUser.hashCode()); Object $createTime = getCreateTime(); result = result * 59 + (($createTime == null) ? 43 : $createTime.hashCode()); Object $lastEditId = getLastEditId(); result = result * 59 + (($lastEditId == null) ? 43 : $lastEditId.hashCode()); Object $lastEditUser = getLastEditUser(); result = result * 59 + (($lastEditUser == null) ? 43 : $lastEditUser.hashCode()); Object $lastEditTime = getLastEditTime(); return result * 59 + (($lastEditTime == null) ? 43 : $lastEditTime.hashCode()); } public String toString() { return "EntityBase(createUserId=" + getCreateUserId() + ", createUser=" + getCreateUser() + ", createTime=" + getCreateTime() + ", lastEditId=" + getLastEditId() + ", lastEditUser=" + getLastEditUser() + ", lastEditTime=" + getLastEditTime() + ")"; }
/*    */   
/* 13 */   public String getCreateUserId() { return this.createUserId; }
/* 14 */   public String getCreateUser() { return this.createUser; }
/* 15 */   public Date getCreateTime() { return this.createTime; }
/* 16 */   public String getLastEditId() { return this.lastEditId; }
/* 17 */   public String getLastEditUser() { return this.lastEditUser; } public Date getLastEditTime() {
/* 18 */     return this.lastEditTime;
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\commonParam\EntityBase.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
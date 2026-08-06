/*    */ package com.digixmed.cloud.icu.pojo.commonParam;
/*    */ 
/*    */ import java.util.Date;
/*    */ 
/*    */ public class DeleteRecord {
/*    */   private String userId;
/*    */   private Date time;
/*    */   private String action;
/*    */   
/*    */   public void setUserId(String userId) {
/* 11 */     this.userId = userId; } public void setTime(Date time) { this.time = time; } public void setAction(String action) { this.action = action; } public boolean equals(Object o) { if (o == this) return true;  if (!(o instanceof DeleteRecord)) return false;  DeleteRecord other = (DeleteRecord)o; if (!other.canEqual(this)) return false;  Object this$userId = getUserId(), other$userId = other.getUserId(); if ((this$userId == null) ? (other$userId != null) : !this$userId.equals(other$userId)) return false;  Object this$time = getTime(), other$time = other.getTime(); if ((this$time == null) ? (other$time != null) : !this$time.equals(other$time)) return false;  Object this$action = getAction(), other$action = other.getAction(); return !((this$action == null) ? (other$action != null) : !this$action.equals(other$action)); } protected boolean canEqual(Object other) { return other instanceof DeleteRecord; } public int hashCode() { int PRIME = 59; result = 1; Object $userId = getUserId(); result = result * 59 + (($userId == null) ? 43 : $userId.hashCode()); Object $time = getTime(); result = result * 59 + (($time == null) ? 43 : $time.hashCode()); Object $action = getAction(); return result * 59 + (($action == null) ? 43 : $action.hashCode()); } public String toString() { return "DeleteRecord(userId=" + getUserId() + ", time=" + getTime() + ", action=" + getAction() + ")"; }
/*    */   
/* 13 */   public String getUserId() { return this.userId; }
/* 14 */   public Date getTime() { return this.time; } public String getAction() {
/* 15 */     return this.action;
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\commonParam\DeleteRecord.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
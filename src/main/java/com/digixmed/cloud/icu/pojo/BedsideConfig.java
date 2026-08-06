/*    */ package com.digixmed.cloud.icu.pojo;
/*    */ 
/*    */ import com.digixmed.cloud.icu.pojo.commonParam.Group;
/*    */ import java.util.Date;
/*    */ import java.util.List;
/*    */ import org.springframework.data.mongodb.core.index.Indexed;
/*    */ import org.springframework.data.mongodb.core.mapping.Document;
/*    */ 
/*    */ @Document("bedsideConfig")
/*    */ @CompoundIndexes({@CompoundIndex(name = "uq_pid_groupName", def = "{pid:-1,groupName:-1}", unique = true)})
/*    */ public class BedsideConfig {
/*    */   @Id
/*    */   private String id;
/*    */   @Indexed
/*    */   private String pid;
/*    */   @Indexed
/*    */   private String groupName;
/*    */   
/* 19 */   public void setId(String id) { this.id = id; } public void setPid(String pid) { this.pid = pid; } public void setGroupName(String groupName) { this.groupName = groupName; } public void setGroups(List<Group> groups) { this.groups = groups; } public void setDeptCode(String deptCode) { this.deptCode = deptCode; } public void setStartTime(Date startTime) { this.startTime = startTime; } public void setEndTime(Date endTime) { this.endTime = endTime; } public boolean equals(Object o) { if (o == this) return true;  if (!(o instanceof BedsideConfig)) return false;  BedsideConfig other = (BedsideConfig)o; if (!other.canEqual(this)) return false;  Object this$id = getId(), other$id = other.getId(); if ((this$id == null) ? (other$id != null) : !this$id.equals(other$id)) return false;  Object this$pid = getPid(), other$pid = other.getPid(); if ((this$pid == null) ? (other$pid != null) : !this$pid.equals(other$pid)) return false;  Object this$groupName = getGroupName(), other$groupName = other.getGroupName(); if ((this$groupName == null) ? (other$groupName != null) : !this$groupName.equals(other$groupName)) return false;  Object<Group> this$groups = (Object<Group>)getGroups(), other$groups = (Object<Group>)other.getGroups(); if ((this$groups == null) ? (other$groups != null) : !this$groups.equals(other$groups)) return false;  Object this$deptCode = getDeptCode(), other$deptCode = other.getDeptCode(); if ((this$deptCode == null) ? (other$deptCode != null) : !this$deptCode.equals(other$deptCode)) return false;  Object this$startTime = getStartTime(), other$startTime = other.getStartTime(); if ((this$startTime == null) ? (other$startTime != null) : !this$startTime.equals(other$startTime)) return false;  Object this$endTime = getEndTime(), other$endTime = other.getEndTime(); return !((this$endTime == null) ? (other$endTime != null) : !this$endTime.equals(other$endTime)); } protected boolean canEqual(Object other) { return other instanceof BedsideConfig; } public int hashCode() { int PRIME = 59; result = 1; Object $id = getId(); result = result * 59 + (($id == null) ? 43 : $id.hashCode()); Object $pid = getPid(); result = result * 59 + (($pid == null) ? 43 : $pid.hashCode()); Object $groupName = getGroupName(); result = result * 59 + (($groupName == null) ? 43 : $groupName.hashCode()); Object<Group> $groups = (Object<Group>)getGroups(); result = result * 59 + (($groups == null) ? 43 : $groups.hashCode()); Object $deptCode = getDeptCode(); result = result * 59 + (($deptCode == null) ? 43 : $deptCode.hashCode()); Object $startTime = getStartTime(); result = result * 59 + (($startTime == null) ? 43 : $startTime.hashCode()); Object $endTime = getEndTime(); return result * 59 + (($endTime == null) ? 43 : $endTime.hashCode()); } public String toString() { return "BedsideConfig(id=" + getId() + ", pid=" + getPid() + ", groupName=" + getGroupName() + ", groups=" + getGroups() + ", deptCode=" + getDeptCode() + ", startTime=" + getStartTime() + ", endTime=" + getEndTime() + ")"; }
/*    */ 
/*    */ 
/*    */ 
/*    */   
/*    */   public String getId() {
/* 25 */     return this.id;
/*    */   } public String getPid() {
/* 27 */     return this.pid;
/*    */   } public String getGroupName() {
/* 29 */     return this.groupName;
/* 30 */   } private String deptCode; private Date startTime; private List<Group> groups = new ArrayList<>(); private Date endTime; public List<Group> getGroups() { return this.groups; }
/* 31 */   public String getDeptCode() { return this.deptCode; }
/* 32 */   public Date getStartTime() { return this.startTime; } public Date getEndTime() {
/* 33 */     return this.endTime;
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\BedsideConfig.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
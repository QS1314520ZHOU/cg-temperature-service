/*    */ package com.digixmed.cloud.icu.pojo;
/*    */ 
/*    */ import java.io.Serializable;
/*    */ import java.util.Date;
/*    */ import java.util.Objects;
/*    */ 
/*    */ public class ReturnTableId implements Serializable {
/*    */   private String patientId;
/*    */   private Date timePoint;
/*    */   private String vitalSigns;
/*    */   private String visitId;
/*    */   
/* 13 */   public void setPatientId(String patientId) { this.patientId = patientId; } public void setTimePoint(Date timePoint) { this.timePoint = timePoint; } public void setVitalSigns(String vitalSigns) { this.vitalSigns = vitalSigns; } public void setVisitId(String visitId) { this.visitId = visitId; } public String toString() { return "ReturnTableId(patientId=" + getPatientId() + ", timePoint=" + getTimePoint() + ", vitalSigns=" + getVitalSigns() + ", visitId=" + getVisitId() + ")"; }
/*    */   
/* 15 */   public String getPatientId() { return this.patientId; }
/* 16 */   public Date getTimePoint() { return this.timePoint; }
/* 17 */   public String getVitalSigns() { return this.vitalSigns; } public String getVisitId() {
/* 18 */     return this.visitId;
/*    */   }
/*    */   
/*    */   public boolean equals(Object o) {
/* 22 */     if (this == o) return true; 
/* 23 */     if (o == null || getClass() != o.getClass()) return false; 
/* 24 */     ReturnTableId that = (ReturnTableId)o;
/* 25 */     return (this.patientId.equals(that.patientId) && this.timePoint
/* 26 */       .equals(that.timePoint) && this.vitalSigns
/* 27 */       .equals(that.vitalSigns) && this.visitId
/* 28 */       .equals(that.visitId));
/*    */   }
/*    */ 
/*    */   
/*    */   public int hashCode() {
/* 33 */     return Objects.hash(new Object[] { this.patientId, this.timePoint, this.vitalSigns, this.visitId });
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\ReturnTableId.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
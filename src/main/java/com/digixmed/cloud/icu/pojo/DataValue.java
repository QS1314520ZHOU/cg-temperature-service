/*     */ package com.digixmed.cloud.icu.pojo;
/*     */ 
/*     */ import java.util.Date;
/*     */ import javax.xml.bind.annotation.XmlAccessType;
/*     */ import javax.xml.bind.annotation.XmlAccessorType;
/*     */ import javax.xml.bind.annotation.XmlElement;
/*     */ import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ @XmlAccessorType(XmlAccessType.FIELD)
/*     */ public class DataValue
/*     */ {
/*     */   @XmlElement(name = "isValid")
/*     */   private int isValid;
/*     */   @XmlElement(name = "mrn")
/*     */   private String mrn;
/*     */   @XmlElement(name = "patientId")
/*     */   private String patientId;
/*     */   @XmlElement(name = "patientName")
/*     */   private String patientName;
/*     */   @XmlElement(name = "planTime")
/*     */   @XmlJavaTypeAdapter(DateAdapter.class)
/*     */   private Date planTime;
/*     */   @XmlElement(name = "recordNurseId")
/*     */   private String recordNurseId;
/*     */   @XmlElement(name = "recordNurseName")
/*     */   private String recordNurseName;
/*     */   @XmlElement(name = "recordTime")
/*     */   @XmlJavaTypeAdapter(DateAdapter.class)
/*     */   private Date recordTime;
/*     */   @XmlElement(name = "series")
/*     */   private String series;
/*     */   @XmlElement(name = "unit")
/*     */   private String unit;
/*     */   @XmlElement(name = "vitalsignNVal1")
/*     */   private String vitalsignNVal1;
/*     */   @XmlElement(name = "vitalsignNVal2")
/*     */   private String vitalsignNVal2;
/*     */   @XmlElement(name = "vitalsignNVal3")
/*     */   private String vitalsignNVal3;
/*     */   @XmlElement(name = "vitalsignName")
/*     */   private String vitalsignName;
/*     */   @XmlElement(name = "vitalsignType")
/*     */   private String vitalsignType;
/*     */   @XmlElement(name = "wardCode")
/*     */   private String wardCode;
/*     */   @XmlElement(name = "remark")
/*     */   private String remark;
/*     */   @XmlElement(name = "vitalsignSVal1")
/*     */   private String vitalsignSVal1;
/*     */   @XmlElement(name = "vitalsignSVal2")
/*     */   private String vitalsignSVal2;
/*     */   @XmlElement(name = "isCustomType")
/*     */   private int isCustomType;
/*     */
/*     */   public void setIsValid(int isValid) {
/*  60 */     this.isValid = isValid;
/*     */   }
/*     */   
/*     */   public int getIsValid() {
/*  64 */     return this.isValid;
/*     */   }
/*     */   
/*     */   public void setMrn(String mrn) {
/*  68 */     this.mrn = mrn;
/*     */   }
/*     */   
/*     */   public String getMrn() {
/*  72 */     return this.mrn;
/*     */   }
/*     */   
/*     */   public void setPatientId(String patientId) {
/*  76 */     this.patientId = patientId;
/*     */   }
/*     */   
/*     */   public String getPatientId() {
/*  80 */     return this.patientId;
/*     */   }
/*     */   
/*     */   public void setPatientName(String patientName) {
/*  84 */     this.patientName = patientName;
/*     */   }
/*     */   
/*     */   public String getPatientName() {
/*  88 */     return this.patientName;
/*     */   }
/*     */   
/*     */   public void setPlanTime(Date planTime) {
/*  92 */     this.planTime = planTime;
/*     */   }
/*     */   
/*     */   public Date getPlanTime() {
/*  96 */     return this.planTime;
/*     */   }
/*     */   
/*     */   public void setRecordNurseId(String recordNurseId) {
/* 100 */     this.recordNurseId = recordNurseId;
/*     */   }
/*     */   
/*     */   public String getRecordNurseId() {
/* 104 */     return this.recordNurseId;
/*     */   }
/*     */   
/*     */   public void setRecordNurseName(String recordNurseName) {
/* 108 */     this.recordNurseName = recordNurseName;
/*     */   }
/*     */   
/*     */   public String getRecordNurseName() {
/* 112 */     return this.recordNurseName;
/*     */   }
/*     */   
/*     */   public void setRecordTime(Date recordTime) {
/* 116 */     this.recordTime = recordTime;
/*     */   }
/*     */   
/*     */   public Date getRecordTime() {
/* 120 */     return this.recordTime;
/*     */   }
/*     */   
/*     */   public void setRemark(String remark) {
/* 124 */     this.remark = remark;
/*     */   }
/*     */   
/*     */   public String getRemark() {
/* 128 */     return this.remark;
/*     */   }
/*     */   
/*     */   public void setSeries(String series) {
/* 132 */     this.series = series;
/*     */   }
/*     */   
/*     */   public String getSeries() {
/* 136 */     return this.series;
/*     */   }
/*     */   
/*     */   public void setUnit(String unit) {
/* 140 */     this.unit = unit;
/*     */   }
/*     */   
/*     */   public String getUnit() {
/* 144 */     return this.unit;
/*     */   }
/*     */   
/*     */   public void setVitalsignNVal1(String vitalsignNVal1) {
/* 148 */     this.vitalsignNVal1 = vitalsignNVal1;
/*     */   }
/*     */   
/*     */   public String getVitalsignNVal1() {
/* 152 */     return this.vitalsignNVal1;
/*     */   }
/*     */   
/*     */   public void setVitalsignNVal2(String vitalsignNVal2) {
/* 156 */     this.vitalsignNVal2 = vitalsignNVal2;
/*     */   }
/*     */   
/*     */   public String getVitalsignNVal2() {
/* 160 */     return this.vitalsignNVal2;
/*     */   }
/*     */
/*     */   public void setVitalsignNVal3(String vitalsignNVal3) {
/*     */     this.vitalsignNVal3 = vitalsignNVal3;
/*     */   }
/*     */
/*     */   public String getVitalsignNVal3() {
/*     */     return this.vitalsignNVal3;
/*     */   }
/*     */
/*     */   public void setVitalsignName(String vitalsignName) {
/* 164 */     this.vitalsignName = vitalsignName;
/*     */   }
/*     */   
/*     */   public String getVitalsignName() {
/* 168 */     return this.vitalsignName;
/*     */   }
/*     */   
/*     */   public void setVitalsignSVal1(String vitalsignSVal1) {
/* 172 */     this.vitalsignSVal1 = vitalsignSVal1;
/*     */   }
/*     */   
/*     */   public String getVitalsignSVal1() {
/* 176 */     return this.vitalsignSVal1;
/*     */   }
/*     */   
/*     */   public void setVitalsignSVal2(String vitalsignSVal2) {
/* 180 */     this.vitalsignSVal2 = vitalsignSVal2;
/*     */   }
/*     */   
/*     */   public String getVitalsignSVal2() {
/* 184 */     return this.vitalsignSVal2;
/*     */   }
/*     */
/*     */   public void setIsCustomType(int isCustomType) {
/*     */     this.isCustomType = isCustomType;
/*     */   }
/*     */
/*     */   public int getIsCustomType() {
/*     */     return this.isCustomType;
/*     */   }
/*     */
/*     */   public void setVitalsignType(String vitalsignType) {
/* 188 */     this.vitalsignType = vitalsignType;
/*     */   }
/*     */   
/*     */   public String getVitalsignType() {
/* 192 */     return this.vitalsignType;
/*     */   }
/*     */   
/*     */   public void setWardCode(String wardCode) {
/* 196 */     this.wardCode = wardCode;
/*     */   }
/*     */   
/*     */   public String getWardCode() {
/* 200 */     return this.wardCode;
/*     */   }
/*     */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\DataValue.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
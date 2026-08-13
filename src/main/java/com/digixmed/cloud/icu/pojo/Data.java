/*    */ package com.digixmed.cloud.icu.pojo;
/*    */ 
/*    */ import java.util.List;
/*    */ import javax.xml.bind.annotation.XmlAccessType;
/*    */ import javax.xml.bind.annotation.XmlAccessorType;
/*    */ import javax.xml.bind.annotation.XmlElement;
/*    */ import javax.xml.bind.annotation.XmlRootElement;
/*    */ 
/*    */ 
/*    */ @XmlRootElement(name = "vitalsignInfoForReqData")
/*    */ @XmlAccessorType(XmlAccessType.FIELD)
/*    */ public class Data
/*    */ {
/*    */   public Data(List<DataValue> data) {
/* 15 */     this.data = data;
/*    */   }
/*    */ 
/*    */   
/*    */   @XmlElement(name = "data", type = DataValue.class)
/*    */   List<DataValue> data;
/*    */   
/*    */   public Data() {}
/*    */   
/*    */   public List<DataValue> getData() {
/* 25 */     return this.data;
/*    */   }
/*    */   
/*    */   public void setData(List<DataValue> data) {
/* 29 */     this.data = data;
/*    */   }
/*    */ 
/*    */   
/*    */   public String toString() {
/* 34 */     return "Data{data=" + this.data + "}";
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\Data.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
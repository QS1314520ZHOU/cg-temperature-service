/*    */ package com.digixmed.cloud.icu.pojo;
/*    */ 
/*    */ import java.text.SimpleDateFormat;
/*    */ import java.util.Date;
/*    */ import javax.xml.bind.annotation.adapters.XmlAdapter;
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ public class DateAdapter
/*    */   extends XmlAdapter<String, Date>
/*    */ {
/* 14 */   private SimpleDateFormat ymdhms = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
/*    */ 
/*    */   
/*    */   public Date unmarshal(String s) throws Exception {
/* 18 */     return this.ymdhms.parse(s);
/*    */   }
/*    */ 
/*    */   
/*    */   public String marshal(Date date) throws Exception {
/* 23 */     return this.ymdhms.format(date);
/*    */   }
/*    */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\DateAdapter.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
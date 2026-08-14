package com.digixmed.cloud.icu.dao;

import cn.hutool.core.bean.BeanUtil;
import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.pojo.ShiftRecord;
import com.digixmed.cloud.icu.service.common.MyConfig;
import com.mongodb.client.result.UpdateResult;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;





@Component
public class MongoDao
{
  @Autowired
  private MongoTemplate mongoTemplate;
  
  public Document getPatientInfo(String pid) {
     return (Document)this.mongoTemplate.findOne(new Query((CriteriaDefinition)Criteria.where("_id").is(new ObjectId(pid))), Document.class, "patient");
  }

  /**
   * 准入原则：patient 集合中存在该 _id 才允许回传。
   * pid 为空、非 ObjectId 或查询异常时返回 null，不抛异常以免中断批次。
   */
  public Document getPatientInfoSafely(String pid) {
     if (pid == null || pid.trim().isEmpty()) {
       return null;
     }
     try {
       return getPatientInfo(pid.trim());
     } catch (Exception e) {
       return null;
     }
  }

  /** patient 集合中是否存在该 _id */
  public boolean existsPatient(String pid) {
     return (getPatientInfoSafely(pid) != null);
  }


  
  public List<Document> selectVitalSignsAfterLastTime(Date lastTime, String[] codes) {
     List<Criteria> orCriteria = new ArrayList<>();
     for (String code : codes) {
       orCriteria.add(Criteria.where("code").is(code).and("editTime").gte(lastTime));
      orCriteria.add(Criteria.where("code").is(code).and("time").gte(lastTime));
    } 
     Query query = new Query();
    Criteria criteria = new Criteria();
    criteria.orOperator(orCriteria.<Criteria>toArray(new Criteria[0]));
     query.addCriteria((CriteriaDefinition)criteria);
    return this.mongoTemplate.find(query, Document.class, "bedside");
  }
  
  public void deleteMessageLog(Date time) {
     Query query = new Query((CriteriaDefinition)Criteria.where("createTime").lte(time));
     this.mongoTemplate.remove(query, IntermediateTable.class);
  }
  
  public List<Document> selectCRSignsBetweenTime(Date startTime, Date endTime, String[] codes) {
     List<Criteria> orCriteria = new ArrayList<>();
    for (String code : codes) {
       Criteria criteria2 = Criteria.where("code").is(code).andOperator(new Criteria[] {
             Criteria.where("time").gte(startTime),
             Criteria.where("time").lt(endTime)
          });
      Criteria criteria1 = Criteria.where("code").is(code).andOperator(new Criteria[] {
             Criteria.where("editTime").gte(startTime),
            Criteria.where("editTime").lt(endTime)
          });
      orCriteria.add(criteria2);
      orCriteria.add(criteria1);
    } 
    Query query = new Query();
    Criteria criteria = new Criteria();
     criteria.orOperator(orCriteria.<Criteria>toArray(new Criteria[0]));
     query.addCriteria((CriteriaDefinition)criteria);
     return this.mongoTemplate.find(query, Document.class, "bedside");
  }
  public List<Document> selectCRSigns(String[] codes) {
     List<Criteria> orCriteria = new ArrayList<>();
    for (String code : codes) {
      Criteria criteria1 = Criteria.where("code").is(code);
      orCriteria.add(criteria1);
    } 
     Query query = new Query();
     Criteria criteria = new Criteria();
    criteria.orOperator(orCriteria.<Criteria>toArray(new Criteria[0]));
     query.addCriteria((CriteriaDefinition)criteria);
    return this.mongoTemplate.find(query, Document.class, "bedside");
  }
  public List<Document> selectAllSignsBetweenTime(Date startTime, Date endTime) {
     List<Criteria> orCriteria = new ArrayList<>();
    
     Criteria criteria1 = Criteria.where("code").ne(null).andOperator(new Criteria[] {
           Criteria.where("time").gte(startTime),
          Criteria.where("time").lt(endTime)
        });
    Criteria criteria2 = Criteria.where("code").ne(null).andOperator(new Criteria[] {
           Criteria.where("editTime").gte(startTime),
           Criteria.where("editTime").lt(endTime)
        });
     orCriteria.add(criteria1);
    orCriteria.add(criteria2);
    
    Query query = new Query();
     Criteria criteria = new Criteria();
     criteria.orOperator(orCriteria.<Criteria>toArray(new Criteria[0]));
     query.addCriteria((CriteriaDefinition)criteria);
    return this.mongoTemplate.find(query, Document.class, "bedside");
  }
  public List<Document> selectAllSigns() {
     return this.mongoTemplate.find(new Query((CriteriaDefinition)Criteria.where("code").ne(null)), Document.class, "bedside");
  }
  
  public List<Document> selectVitalSignsAfterLastTime(Date lastTime, String code) {
     Query query = null;
    if ("param_daBianAmount".equals(code)) {
      query = new Query((CriteriaDefinition)Criteria.where("code").in((Object[])MyConfig.DBCODES).and("editTime").gt(lastTime));
    } else {
      query = new Query((CriteriaDefinition)Criteria.where("code").is(code).and("editTime").gt(lastTime));
    } 
     return this.mongoTemplate.find(query, Document.class, "bedside");
  }
  
  public List<Document> selectBedSidesRDXWithCode(Date RDXlastTime, Date RDXthisTime, String pid, String[] codes) {
     Query query = new Query((CriteriaDefinition)Criteria.where("code").in((Object[])codes).and("valid").is(Boolean.TRUE).and("pid").is(pid).andOperator(new Criteria[] {
             Criteria.where("time").gte(RDXlastTime),
             Criteria.where("time").lt(RDXthisTime)
          }));
    
     query.with(Sort.by(new Sort.Order[] { Sort.Order.asc("editTime") }));
     List<Document> result = this.mongoTemplate.find(query, Document.class, "bedside");
     return result;
  }


  public List<Document> selectBedSidesRDXWithCode(Date RDXlastTime, Date RDXthisTime, String pid, List<String> codes) {
     Query query = new Query((CriteriaDefinition)Criteria.where("code").in(codes).and("valid").is(Boolean.TRUE).and("pid").is(pid).andOperator(new Criteria[] {
            Criteria.where("time").gte(RDXlastTime),
            Criteria.where("time").lt(RDXthisTime)
          }));
    
     query.with(Sort.by(new Sort.Order[] { Sort.Order.asc("editTime") }));
     List<Document> result = this.mongoTemplate.find(query, Document.class, "bedside");
     return result;
  }
  
  public List<Document> selectVitalSignsAfterLastTime(Date lastTime, String codes, String pid) {
     Query query = new Query((CriteriaDefinition)Criteria.where("code").is(codes).and("pid").is(pid).and("editTime").gt(lastTime));
    return this.mongoTemplate.find(query, Document.class, "bedside");
  }
  
  public Document selectPatientByPid(String id) {
     if (id == null || "".equals(id)) {
       return null;
    }
     Query query = new Query((CriteriaDefinition)Criteria.where("_id").is(new ObjectId(id)));
     Document result = (Document)this.mongoTemplate.findOne(query, Document.class, "patient");
     return result;
  }
  
  public Document selectDoctorByAccountId(String accountId) {
     if (accountId == null || accountId.length() != 24) {
       return null;
    }
     Query query = new Query((CriteriaDefinition)Criteria.where("_id").is(new ObjectId(accountId)));
     Document result = (Document)this.mongoTemplate.findOne(query, Document.class, "account");
     return result;
  }
  
  public Document selectBedSideByCodeAndTime(String pid, String code, Date time) {
    Query query = new Query((CriteriaDefinition)Criteria.where("pid").is(pid).and("code").is(code).and("time").is(time));
    return (Document)this.mongoTemplate.findOne(query, Document.class, "bedside");
  }
  
  public Document selectBedSideById(String bedsideId) {
     Query query = new Query((CriteriaDefinition)Criteria.where("_id").is(new ObjectId(bedsideId)));
     return (Document)this.mongoTemplate.findOne(query, Document.class, "bedside");
  }
  
  public Document selectVaildBedSideByCodeAndTime(String pid, String code, Date time) {
     Query query = new Query((CriteriaDefinition)Criteria.where("pid").is(pid).and("code").is(code).and("time").is(time).and("valid").is(Boolean.valueOf(true)));
     return (Document)this.mongoTemplate.findOne(query, Document.class, "bedside");
  }

  
  public Document selectBedSideByTimeAndIdAndCode(String pid, String code, Date time) {
     Query query = new Query((CriteriaDefinition)Criteria.where("pid").is(pid).and("code").is(code).and("time").is(time));
     return (Document)this.mongoTemplate.findOne(query, Document.class, "bedside");
  }
  
  public List<Document> selectAdmittedPatients() {
     Query query = new Query((CriteriaDefinition)Criteria.where("status").is("admitted"));
     return this.mongoTemplate.find(query, Document.class, "patient");
  }


  /**
   * 诊断用：查询 (time, time+1小时] 内所有 param_T 记录，不过滤 valid，
   * 用于区分“确实没有复测数据”与“有数据但被 valid 等条件过滤”。
   */
  public List<Document> selectRecheckTemperatureDiagnostic(String pid, Date time) {
     if (pid == null || time == null) {
       return new ArrayList<>();
     }
     Date end = new Date(time.getTime() + 3600000L);
     Query query = new Query((CriteriaDefinition)Criteria.where("pid").is(pid).and("code").is("param_T").andOperator(new Criteria[] {
             Criteria.where("time").gt(time),
             Criteria.where("time").lte(end)
          }));
     query.with(Sort.by(new Sort.Order[] { Sort.Order.asc("time") }));
     List<Document> docs = this.mongoTemplate.find(query, Document.class, "bedside");
     return (docs == null) ? new ArrayList<>() : docs;
  }

  /**
   * 查询指定时间节点、指定 code 的 bedside 记录（valid 不为 false）。
   * 用于取 param_Yishi（记录护士）与 param_tiWenBuWei（体温部位）。
   */
  public Document selectBedSideByCodeAndTimePoint(String pid, String code, Date time) {
     if (pid == null || code == null || time == null) {
       return null;
     }
     Query query = new Query((CriteriaDefinition)Criteria.where("pid").is(pid).and("code").is(code).and("time").is(time).and("valid").ne(Boolean.FALSE));
     query.with(Sort.by(new Sort.Order[] { Sort.Order.desc("editTime") }));
     return (Document)this.mongoTemplate.findOne(query, Document.class, "bedside");
  }

  public String getTheAccountIdInTime(String pid, Date timePoint) {
    String accountId = null;
    Query query = new Query((CriteriaDefinition)Criteria.where("pid").is(pid).andOperator(new Criteria[] {
            Criteria.where("startTime").lte(timePoint),
             Criteria.where("endTime").gte(timePoint)
          }));
    
     ShiftRecord one = (ShiftRecord)this.mongoTemplate.findOne(query, ShiftRecord.class);
     if (one != null) {
       accountId = one.getAccountId();
    }
     return accountId;
  }

  public List<Document> selectBedSidesRDXWithCode(Date RDXlastTime, Date RDXthisTime, String pid, String code) {
     Query query = new Query((CriteriaDefinition)Criteria.where("code").is(code).and("valid").is(Boolean.TRUE).and("pid").is(pid).andOperator(new Criteria[] {
             Criteria.where("time").gte(RDXlastTime),
             Criteria.where("time").lt(RDXthisTime)
          }));
    
     query.with(Sort.by(new Sort.Order[] { Sort.Order.asc("editTime") }));
     List<Document> result = this.mongoTemplate.find(query, Document.class, "bedside");
     return result;
  }

  
  public List<Document> getTube(String pid) {
    Query query = new Query((CriteriaDefinition)Criteria.where("pid").is(pid).and("type").is("尿管"));
     return this.mongoTemplate.find(query, Document.class, "tubeExe");
  }

  
  public IntermediateTable selectOneIntermediateTable(String id) {
     Query query = new Query((CriteriaDefinition)Criteria.where("id").is(id));
     return (IntermediateTable)this.mongoTemplate.findOne(query, IntermediateTable.class);
  }

  
  public IntermediateTable selectIntermediateTableByTimeAndIdAndCode(String mrn, String signCode, Date timePoint) {
    Query query = new Query((CriteriaDefinition)Criteria.where("mrn").is(mrn).and("signCode").is(signCode).and("timePoint").is(timePoint));
     return (IntermediateTable)this.mongoTemplate.findOne(query, IntermediateTable.class);
  }
  
  public void saveIntermediateTable(IntermediateTable tableInfo) {
    this.mongoTemplate.save(tableInfo);
  }

  public Boolean updateIntermediateTable(IntermediateTable lastTableInfo, IntermediateTable tableInfo) {
     if (tableInfo != null && lastTableInfo != null) {
       Update update = new Update();
       update.set("LastEditTime", tableInfo.getLastEditTime());
       update.set("signValue", tableInfo.getSignValue());
       update.set("signValue2", tableInfo.getSignValue2());
       update.set("recheckAttempts", tableInfo.getRecheckAttempts());
       update.set("recheckDone", tableInfo.getRecheckDone());
       update.set("signLocation", tableInfo.getSignLocation());
       update.set("isValid", tableInfo.getIsValid());
      update.set("isUpload", Integer.valueOf(0));
       update.set("failed", Integer.valueOf(0));
       update.set("authorName", tableInfo.getAuthorName());
      update.set("authorId", tableInfo.getAuthorId());
      update.set("chlidList", tableInfo.getChlidList());
      Query query = Query.query((CriteriaDefinition)Criteria.where("id").is(lastTableInfo.getId()));
      UpdateResult updateResult = this.mongoTemplate.updateMulti(query, update, IntermediateTable.class);
      
       return Boolean.valueOf((updateResult.getModifiedCount() != 0L));
    } 
     return Boolean.valueOf(false);
  }
  
  public void updateSuccessLog(IntermediateTable tableInfo, Boolean isSuccess, String error) {
    Update update = new Update();
     update.set("isUpload", isSuccess ? Integer.valueOf(1) : Integer.valueOf(0));
     if (!isSuccess.booleanValue()) {
       update.set("failed", Integer.valueOf(1));
       update.set("errorMsg", error);
    } else {
       update.set("failed", Integer.valueOf(0));
      update.set("isUpload", Integer.valueOf(1));
    }
    update.set("requestMsg", tableInfo.getRequestMsg());
     update.set("reponseMsg", tableInfo.getReponseMsg());
     update.set("returnTime", tableInfo.getReturnTime());
    
     this.mongoTemplate.updateFirst(Query.query((CriteriaDefinition)Criteria.where("id").is(tableInfo.getId())), update, IntermediateTable.class);
  }

  
  private Update createUpdate(Object entity, Object dto) {
    Update update = new Update();
     Map<String, Object> map = BeanUtil.beanToMap(entity);
    for (Map.Entry<String, Object> entry : (Iterable<Map.Entry<String, Object>>)BeanUtil.beanToMap(dto).entrySet()) {
       if ((map.get(entry.getKey()) == null || entry.getValue() == null || !map.get(entry.getKey()).equals(entry.getValue())) && (
        map.get(entry.getKey()) != null || entry.getValue() != null)) {
           update.set(entry.getKey(), entry.getValue());
      }
    } 
    
    if (update.getUpdateObject().size() == 0) {
    return null;
    }
     return update;
  }

  
  /** 只领取旧回传链路自己写入的记录（signCode 必存在），防止误领取历史遗留的推送链路文档 */
  public List<IntermediateTable> selectNoUploadInfo() {
     Query query = new Query((CriteriaDefinition)Criteria.where("isUpload").ne(Integer.valueOf(1)).and("failed").ne(Integer.valueOf(1)).and("signCode").exists(true));
     return this.mongoTemplate.find(query, IntermediateTable.class);
  }
  
  public Document selectPatientWaitDischarged(String pid) {
    Query query = new Query((CriteriaDefinition)Criteria.where("_id").is(pid).and("status").is("wait_discharged"));
    return (Document)this.mongoTemplate.findOne(query, Document.class, "patient");
  }
  public Document selectPatientDischarged(String pid) {
    Query query = new Query((CriteriaDefinition)Criteria.where("_id").is(pid).and("status").is("discharged"));
     return (Document)this.mongoTemplate.findOne(query, Document.class, "patient");
  }
  public List<IntermediateTable> selectNoUploadInfo(String[] pid) {
     Query query = new Query((CriteriaDefinition)Criteria.where("isUpload").ne(Integer.valueOf(1)).and("failed").ne(Integer.valueOf(1)).and("signCode").exists(true).and("patientId").in((Object[])pid));
    return this.mongoTemplate.find(query, IntermediateTable.class);
  }
  
  /**
   * 查询需要继续巡检复测的体温中间表记录：recheckDone != 1 且 timePoint 在指定时间之后。
   * @param earliestTimePoint 只巡检 timePoint 大于等于该时间的记录，避免全表扫描历史数据
   */
  public List<IntermediateTable> selectRecheckPendingList(Date earliestTimePoint) {
     Criteria criteria = Criteria.where("signCode").is("param_T").and("recheckDone").ne(Integer.valueOf(1));
     if (earliestTimePoint != null) {
       criteria = criteria.and("timePoint").gte(earliestTimePoint);
     }
     Query query = new Query((CriteriaDefinition)criteria);
     query.with(Sort.by(new Sort.Order[] { Sort.Order.asc("timePoint") }));
     return this.mongoTemplate.find(query, IntermediateTable.class);
  }

  /**
   * 回写体温复测巡检结果。
   * @param id 中间表记录id
   * @param signValue2 复测值，仅 needResend=true 时写入
   * @param attempts 已查询次数
   * @param done 是否结束巡检
   * @param needResend 是否需要重新回传（置 isUpload=0，由回传任务重发）
   */
  public void updateRecheckResult(String id, String signValue2, int attempts, boolean done, boolean needResend) {
     if (id == null) {
       return;
     }
     Update update = new Update();
     update.set("recheckAttempts", Integer.valueOf(attempts));
     update.set("recheckDone", Integer.valueOf(done ? 1 : 0));
     if (needResend) {
       update.set("signValue2", signValue2);
       update.set("isUpload", Integer.valueOf(0));
       update.set("failed", Integer.valueOf(0));
       update.set("errorMsg", "");
     }
     this.mongoTemplate.updateFirst(Query.query((CriteriaDefinition)Criteria.where("id").is(id)), update, IntermediateTable.class);
  }

  /**
   * 查询体温复测记录：(time, time+1小时] 区间内、同一病人、有效的 param_T 记录，按 time 升序
   * @param pid 病人的mongo id
   * @param time 原始体温记录的 bedside.time
   * @param excludeBedsideId 需要排除的原始记录id
   */
  public List<Document> selectRecheckTemperature(String pid, Date time, String excludeBedsideId) {
     List<Document> result = new ArrayList<>();
     if (pid == null || time == null) {
       return result;
     }
     Date end = new Date(time.getTime() + 3600000L);
     Query query = new Query((CriteriaDefinition)Criteria.where("pid").is(pid).and("code").is("param_T").and("valid").ne(Boolean.FALSE).andOperator(new Criteria[] {
             Criteria.where("time").gt(time),
             Criteria.where("time").lte(end)
          }));
     query.with(Sort.by(new Sort.Order[] { Sort.Order.asc("time") }));
     List<Document> docs = this.mongoTemplate.find(query, Document.class, "bedside");
     if (docs == null) {
       return result;
     }
     for (Document doc : docs) {
       Object id = doc.get("_id");
       if (excludeBedsideId != null && id != null && excludeBedsideId.equals(id.toString())) {
         continue;
       }
       result.add(doc);
     }
     return result;
  }

  public void updateFailLog(String patientId, String signName, Date timePoint, String error) {
     if (patientId != null && signName != null && timePoint != null) {
      Update update = new Update();
      update.set("failed", Integer.valueOf(1));
      update.set("failReason", error);
      this.mongoTemplate.updateMulti(Query.query((CriteriaDefinition)Criteria.where("signName").is(signName).and("timePoint").is(timePoint).and("patientId").is(patientId)), update, IntermediateTable.class);
    } 
  }
}

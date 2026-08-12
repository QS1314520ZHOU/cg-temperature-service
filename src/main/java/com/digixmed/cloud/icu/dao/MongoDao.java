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

  
  public List<IntermediateTable> selectNoUploadInfo() {
     Query query = new Query((CriteriaDefinition)Criteria.where("isUpload").ne(Integer.valueOf(1)).and("failed").ne(Integer.valueOf(1)));
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
     Query query = new Query((CriteriaDefinition)Criteria.where("isUpload").ne(Integer.valueOf(1)).and("failed").ne(Integer.valueOf(1)).and("patientId").in((Object[])pid));
    return this.mongoTemplate.find(query, IntermediateTable.class);
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

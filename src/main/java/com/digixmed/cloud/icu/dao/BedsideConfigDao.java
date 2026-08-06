package com.digixmed.cloud.icu.dao;

import com.digixmed.cloud.icu.pojo.BedsideConfig;
import com.digixmed.cloud.icu.pojo.commonParam.ConfigUnit;
import com.digixmed.cloud.icu.pojo.configParam.ConfigInOutVolume;
import com.digixmed.cloud.icu.pojo.configParam.ConfigParam;
import com.digixmed.cloud.icu.pojo.paramConfig.ConfigParamDto;
import com.digixmed.cloud.icu.pojo.tubeExe.TubeExe;
import com.digixmed.cloud.icu.service.common.EntityConvertUtils;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;





@Component
public class BedsideConfigDao
{
  @Autowired
  private MongoTemplate mongoTemplate;
  
  public BedsideConfig findByPidAndGroupName(String pid, String groupName) {
    return (BedsideConfig)this.mongoTemplate.findOne(new Query((CriteriaDefinition)Criteria.where("pid").is(pid).and("groupName").is(groupName)), BedsideConfig.class);
  }
  
  public List<ConfigUnit> findAllConfigUnit() {
     return this.mongoTemplate.findAll(ConfigUnit.class);
  }
  
  public List<ConfigParam> findAllConfigParam() {
     return this.mongoTemplate.findAll(ConfigParam.class);
  }


  
  public List<TubeExe> getTubeExesByPidAndBetweenTime(String pid, Date startTime, Date endTime) {
     if (Objects.isNull(startTime) && Objects.isNull(endTime)) {
       return this.mongoTemplate.find(new Query((CriteriaDefinition)Criteria.where("pid").is(pid)), TubeExe.class);
    }
     List<TubeExe> ret = new ArrayList<>();
    
     Criteria criteria1 = Criteria.where("pid").is(pid).andOperator(new Criteria[] { Criteria.where("startTime").gte(startTime).lt(endTime) });
    
     Criteria criteria2 = Criteria.where("pid").is(pid).andOperator(new Criteria[] { Criteria.where("startTime").lt(startTime),
          Criteria.where("endTime").gt(startTime) });
    
     Criteria criteria3 = Criteria.where("pid").is(pid).andOperator(new Criteria[] { Criteria.where("startTime").lt(startTime),
           Criteria.where("endTime").is(null) });
     List<TubeExe> listMono1 = this.mongoTemplate.find(Query.query((CriteriaDefinition)criteria1.and("valid").ne(Boolean.valueOf(false))), TubeExe.class);
    List<TubeExe> listMono2 = this.mongoTemplate.find(Query.query((CriteriaDefinition)criteria2.and("valid").ne(Boolean.valueOf(false))), TubeExe.class);
    List<TubeExe> listMono3 = this.mongoTemplate.find(Query.query((CriteriaDefinition)criteria3.and("valid").ne(Boolean.valueOf(false))), TubeExe.class);
     List<TubeExe> t1 = listMono1;
     List<TubeExe> t2 = listMono2;
     List<TubeExe> t3 = listMono3;
    
    t1.addAll(t2);
     t1.addAll(t3);
    
    List<TubeExe> tubeExeList = (List<TubeExe>)t1.stream().distinct().collect(Collectors.toList());
     return tubeExeList;
  }
  
  public ConfigInOutVolume findOneByDept(String deptCode) {
     Criteria criteria = Criteria.where("deptCode").is(deptCode);
     return (ConfigInOutVolume)this.mongoTemplate.findOne(Query.query((CriteriaDefinition)criteria), ConfigInOutVolume.class);
  }
  
  public List<ConfigParamDto> getConfigParamListByCodeList(List<String> codeList) {
     List<ConfigParam> configParamFlux = this.mongoTemplate.find(new Query((CriteriaDefinition)Criteria.where("code").in(codeList)), ConfigParam.class);
     return EntityConvertUtils.copyList(configParamFlux, ConfigParamDto.class);
  }
}

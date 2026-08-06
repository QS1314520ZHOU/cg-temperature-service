 package com.digixmed.cloud.icu.service.common;
 import cn.hutool.core.bean.BeanUtil;
 import cn.hutool.core.collection.CollectionUtil;
 import cn.hutool.core.util.ObjectUtil;
 import cn.hutool.core.util.StrUtil;
 import com.digixmed.cloud.icu.dao.BedsideConfigDao;
 import com.digixmed.cloud.icu.dao.MongoDao;
 import com.digixmed.cloud.icu.pojo.BedsideConfig;
 import com.digixmed.cloud.icu.pojo.commonParam.ConfigItemDto;
 import com.digixmed.cloud.icu.pojo.commonParam.ConfigUnit;
 import com.digixmed.cloud.icu.pojo.commonParam.Group;
 import com.digixmed.cloud.icu.pojo.commonParam.Item;
 import com.digixmed.cloud.icu.pojo.configParam.BedsideConfigDto;
 import com.digixmed.cloud.icu.pojo.configParam.ConfigInOutVolume;
 import com.digixmed.cloud.icu.pojo.configParam.ConfigParam;
 import com.digixmed.cloud.icu.pojo.paramConfig.ConfigParamDto;
 import com.digixmed.cloud.icu.pojo.tubeExe.TubeExe;
 import com.digixmed.cloud.icu.pojo.tubeExe.TubeExeDto;
 import com.digixmed.cloud.icu.util.DateUtils;
 import com.digixmed.cloud.icu.util.TubeUtil;
 import java.util.ArrayList;
 import java.util.Date;
 import java.util.HashMap;
 import java.util.List;
 import java.util.Map;
 import java.util.Objects;
 import java.util.stream.Collectors;
 import javax.annotation.Resource;
 import org.bson.Document;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.springframework.beans.factory.annotation.Autowired;
 import org.springframework.stereotype.Service;
 
 @Service
 public class BedsideConfigDBService {
  private static final Logger log = LoggerFactory.getLogger(BedsideConfigDBService.class);
 
 
   
   @Autowired
   private BedsideConfigDao bedsideConfigRepository;
 
 
   
   @Autowired
   private MongoDao mongoDao;
 
 
   
   private static final String DEFAULT = "default";
 
   
   @Resource
   private TubeUtil tubeUtil;
 
 
   
   private BedsideConfigDto getInOutVolumeBedsideConfig(BedsideConfigDto bedsideConfigDto, List<ConfigParam> configParamList, String pid, Date startTime, Date endTime) {
    List<TubeExe> tubeExesObject = this.bedsideConfigRepository.getTubeExesByPidAndBetweenTime(pid, startTime, endTime);
     List<TubeExeDto> tubeExeDtoList = EntityConvertUtils.copyList(tubeExesObject, TubeExeDto.class);
     List<TubeExeDto> tubeExeYLGList = this.tubeUtil.getInDayYlgList(startTime, endTime, tubeExeDtoList);
     ConfigInOutVolume configInOutVolume = this.bedsideConfigRepository.findOneByDept(bedsideConfigDto.getDeptCode());
     
     List<String> codeList = new ArrayList<>();
     for (TubeExeDto tubeExeDto : tubeExeYLGList) {
      String outParamCode = "param_tube_" + tubeExeDto.getName();
       codeList.add(outParamCode);
     } 
     
    List<ConfigParamDto> configParamDtos = this.bedsideConfigRepository.getConfigParamListByCodeList(codeList);
    List<String> paramCodeList = (List<String>)configParamDtos.stream().map(ConfigParamDto::getCode).collect(Collectors.toList());
     codeList.removeAll(paramCodeList);
     List<ConfigParamDto> configParamDtoList = new ArrayList<>();
     for (String code : codeList) {
       String[] split = code.split("_");
       ConfigParamDto configParamDto = this.tubeUtil.getConfigParamDto(split[split.length - 1], "out", "num", code);
       configParamDtoList.add(configParamDto);
     } 
     
     Map<String, String> code2ParamNameMap = (Map<String, String>)configParamDtos.stream().collect(Collectors.toMap(ConfigParamDto::getCode, ConfigParamDto::getName, (o1, o2) -> o1));
     List<Group> groups = bedsideConfigDto.getGroups();
    for (Group group : groups) {
      if ("出量".equalsIgnoreCase(group.getName())) {
         List<Item> items = group.getItems();
         for (TubeExeDto tubeExeDto : tubeExeYLGList) {
           Item itemByTube = this.tubeUtil.getItemByTube(tubeExeDto, code2ParamNameMap);
          if (configInOutVolume != null && configInOutVolume.getEnableTubeRemark().booleanValue()) {
             itemByTube.setRemark(true);
           }
          List<String> itemCodeList = (List<String>)items.stream().map(Item::getCode).collect(Collectors.toList());
          if (!itemCodeList.contains(itemByTube.getCode())) items.add(itemByTube);
         } 
       } 
     } 
     return bedsideConfigDto;
   }
   
   public List<String> getInOutBedSideCode(String pid, Date startTime, Boolean inOut) {
     Date endTime = DateUtils.getTomorrow(startTime);
    startTime = DateUtils.getTimeSecond(startTime);
     Document patientInfo = this.mongoDao.getPatientInfo(pid);
     if (ObjectUtil.isEmpty(patientInfo)) {
      log.info("id为：" + pid + "的病人不存在");
      return null;
     } 
     String deptCode = patientInfo.getString("deptCode");
     BedsideConfigDto bedsideConfigDto = getBedsideConfigByPidAndDeptCodeAndGroupName(pid, deptCode, "出入量", startTime, endTime);
     String name = inOut.booleanValue() ? "入量" : "出量";
     String calculation = inOut.booleanValue() ? "in" : "out";
     List<String> result = new ArrayList<>();
    for (Group gp : bedsideConfigDto.getGroups()) {
       if (name.equals(gp.getName())) {
         result = (List<String>)gp.getItems().stream().filter(item -> calculation.equals(item.getCalculation())).map(Item::getCode).collect(Collectors.toList());
       }
     } 
     return result;
   }
 
   
   public BedsideConfigDto getBedsideConfigByPidAndDeptCodeAndGroupName(String pid, String deptCode, String groupName, Date startTime, Date endTime) {
     List<ConfigParam> configParamList = this.bedsideConfigRepository.findAllConfigParam();
     BedsideConfig bedsideConfig = getBedsideConfigByPidAndGroupName(pid, deptCode, groupName, configParamList);
     BedsideConfigDto bedsideConfigDto = new BedsideConfigDto();
     BeanUtil.copyProperties(bedsideConfig, bedsideConfigDto, false);
     if ("出入量".equals(groupName)) {
       bedsideConfigDto.setDeptCode(deptCode);
      return getInOutVolumeBedsideConfig(bedsideConfigDto, configParamList, pid, startTime, endTime);
     } 
     return bedsideConfigDto;
   }
   
   public BedsideConfig getBedsideConfigByPidAndGroupName(String pid, String deptCode, String groupName, List<ConfigParam> configParamList) {
    BedsideConfig bedsideConfig = this.bedsideConfigRepository.findByPidAndGroupName(pid, groupName);
     if (Objects.isNull(bedsideConfig) || StrUtil.isBlank(bedsideConfig.getId())) {
      bedsideConfig = this.bedsideConfigRepository.findByPidAndGroupName(deptCode, groupName);
     }
    if (Objects.isNull(bedsideConfig) || StrUtil.isBlank(bedsideConfig.getId())) {
      bedsideConfig = this.bedsideConfigRepository.findByPidAndGroupName("default", groupName);
     }
 
     
     List<ConfigUnit> unitList = this.bedsideConfigRepository.findAllConfigUnit();
    List<ConfigParam> paramList = this.bedsideConfigRepository.findAllConfigParam();
     Map<String, ConfigUnit> configUnitMap = new HashMap<>();
    unitList.forEach(configUnit -> configUnitMap.put(configUnit.getCode(), configUnit));
    Map<String, ConfigParam> configParamMap = new HashMap<>();
     paramList.forEach(configParam -> configParamMap.put(configParam.getCode(), configParam));
     for (Group group : bedsideConfig.getGroups()) {
       for (Item item : group.getItems()) {
        ConfigParam param = configParamMap.get(item.getCode());
        if (Objects.isNull(param))
           continue;  String validMax = param.getValidMax();
         String validMin = param.getValidMin();
        BeanUtil.copyProperties(param, item, true);
         if (Objects.nonNull(validMax) && Objects.nonNull(validMin)) {
           item.setValidMax(getFloatVal(validMax));
           item.setValidMin(getFloatVal(validMin));
         } 
        List<ConfigItemDto> configItemList = item.getConfigItemList();
        if (CollectionUtil.isNotEmpty(configItemList)) configItemList.clear();
         if (CollectionUtil.isNotEmpty(param.getConfigItemList())) {
          configItemList.addAll(EntityConvertUtils.copyList(param.getConfigItemList(), ConfigItemDto.class));
         }
         if (CollectionUtil.isEmpty(configItemList)) {
           configItemList = new ArrayList<>();
           item.setConfigItemList(configItemList);
          List<String> params = param.getParams();
          if (CollectionUtil.isNotEmpty(params)) {
             for (String ss : params) {
               configItemList.add(new ConfigItemDto(ss, ss));
             }
           }
         } 
        ConfigUnit configUnit = configUnitMap.get(param.getUnitCode());
         if (configUnit != null) item.setUnit(configUnit.getName());
       } 
     } 
    return bedsideConfig;
   }
   
   private Float getFloatVal(String validMax) {
     try {
       return Float.valueOf(Float.parseFloat(validMax));
     } catch (Exception exception) {
       
       return null;
     } 
   }
 }

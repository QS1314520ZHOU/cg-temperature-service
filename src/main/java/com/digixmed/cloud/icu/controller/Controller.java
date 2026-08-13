 package com.digixmed.cloud.icu.controller;
 
 import cn.hutool.core.date.DateTime;
 import cn.hutool.core.date.DateUtil;
 import com.digixmed.cloud.icu.service.common.DataService;
 import com.digixmed.cloud.icu.service.common.ReturnService;
 import com.digixmed.cloud.icu.util.DateUtils;
 import io.swagger.annotations.Api;
 import io.swagger.annotations.ApiImplicitParam;
 import io.swagger.annotations.ApiImplicitParams;
 import io.swagger.annotations.ApiOperation;
 import java.text.ParseException;
 import java.util.Date;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.springframework.beans.factory.annotation.Autowired;
 import org.springframework.web.bind.annotation.GetMapping;
 import org.springframework.web.bind.annotation.PostMapping;
 import org.springframework.web.bind.annotation.RequestParam;
 import org.springframework.web.bind.annotation.RestController;
 
 
 
 
 
 
 @RestController
 @Api(value = "体温单回传接口", tags = {"体温单回传接口"})
 public class Controller
 {
  private static final Logger log = LoggerFactory.getLogger(Controller.class);
   
   @Autowired
   DataService dataService;
   
   @Autowired
   ReturnService returnService;
 
   
   @PostMapping({"/rewirte"})
   @ApiOperation(value = "观察项统计", notes = "观察项统计")
   @ApiImplicitParam(name = "data", value = "结束时间yyyy-MM-dd的格式", required = true, dataType = "String", paramType = "query")
   public String rewirte(@RequestParam("data") String date) {
     this.dataService.selectVitalSignsAfterLastTime((Date)DateUtil.parse(date, "yyyy-MM-dd"));
    return "没有bug";
   }
   
   @GetMapping({"/test1"})
   @ApiOperation(value = "查询病人入科时的第一个条体征记录", notes = "查询病人入科时的第一个条体征记录")
   public String test1() {
     this.dataService.selectPatientsInDepts();
     return "没有bug";
   }
 
 
 
 
 
   
   @PostMapping({"/clo"})
   @ApiOperation(value = "出入量统计", notes = "出入量统计")
   @ApiImplicitParams({@ApiImplicitParam(name = "data", value = "时间格式yyyy-MM-dd HH:mm:ss", required = true, dataType = "Date", paramType = "query"), @ApiImplicitParam(name = "type", value = "输入类型: 0-入量、1-出量、2-其他出量、3-出入量", required = true, dataType = "String", paramType = "query")})
   public String newcl(@RequestParam("data") String date, @RequestParam(value = "type", required = false, defaultValue = "3") String type) throws ParseException {
     Date date1 = DateUtils.getDateTime1(date);
     if ("0".equals(type)) {
      this.dataService.selectCRLiangAfterLastTime_new(date1, Boolean.valueOf(true), Integer.valueOf(1), Integer.valueOf(7));
    } else if ("1".equals(type)) {
      this.dataService.selectCRLiangAfterLastTime_new(date1, Boolean.valueOf(false), Integer.valueOf(1), Integer.valueOf(7));
    } else if ("2".equals(type)) {
      this.dataService.selectChuLiangOtherAfterLastTime_new(date1, Integer.valueOf(1), Integer.valueOf(7));
     } else {
      log.info("测试开始，测试时间为" + date1);
      this.dataService.selectCRLiangAfterLastTime_new(date1, Boolean.valueOf(true), Integer.valueOf(1), Integer.valueOf(7));
      this.dataService.selectCRLiangAfterLastTime_new(date1, Boolean.valueOf(false), Integer.valueOf(1), Integer.valueOf(7));
       this.dataService.selectChuLiangOtherAfterLastTime_new(date1, Integer.valueOf(1), Integer.valueOf(7));
      this.dataService.selectDXLiangAfterLastTime(date1, Integer.valueOf(1), Integer.valueOf(7));
     } 
    return "没有bug";
   }
 
 
   
   @PostMapping({"/testxy"})
   @ApiOperation(value = "普通体征方法血压", notes = "普通体征方法血压")
   @ApiImplicitParam(name = "data", value = "结束时间yyyy-MM-dd的格式", required = true, dataType = "String", paramType = "query")
   public String testxy(@RequestParam("data") String date) {
     DateTime dateTimes = DateUtil.parse(date, "yyyy-MM-dd");
     this.dataService.selectVitalSignsAfterLastTimeXY((Date)dateTimes);
    return "没有bug";
   }
   
   @GetMapping({"/test4"})
   @ApiOperation(value = "体温单回传", notes = "体温单回传")
   public String test4() {
     this.returnService.uploadInfo();
     return "没有bug";
   }
 
 
   
   @GetMapping({"/rewirteByBedSideId"})
   @ApiOperation(value = "统计一条体征数据", notes = "统计一条体征数据")
   @ApiImplicitParam(name = "id", value = "bedSide的ID mongoDB的ID", required = true, dataType = "String", paramType = "query")
   public String rewirteByBedSideId(@RequestParam("id") String id) {
     return this.dataService.selectOneVitalSign(id) ? "没有bug" : "有bug";
   }
 }
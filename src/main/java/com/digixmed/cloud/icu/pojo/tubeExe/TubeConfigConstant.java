/*     */ package com.digixmed.cloud.icu.pojo.tubeExe;
/*     */ 
/*     */ import java.util.HashMap;
/*     */ import java.util.Map;
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ 
/*     */ public class TubeConfigConstant
/*     */ {
/*     */   public static final String START_TIME = "start_time";
/*     */   public static final String END_TIME = "end_time";
/*     */   public static final String ZHI_GUAN_DAYS = "zhi_guan_days";
/*     */   public static final String TUBEACTION_RECORDER_CODE = "tube_action_recorded";
/*     */   public static final String TUBEACTION_RECORDED = "记录者";
/*     */   public static final String TUBE_NAME = "tube_name";
/*     */   public static final String TUBE_BODY = "body";
/*     */   public static final String TUBE_LOCATION = "tubeLocation";
/*     */   public static final String TUBE_SIZE = "size";
/*     */   public static final String CASING_SIZE = "casingSize";
/*     */   public static final String TUBE_MATERIAL = "material";
/*     */   public static final String URINE_BAG_TYPE = "urineBagType";
/*     */   public static final String URINE_LUMEN_NUM = "urineLumenNum";
/*     */   public static final String CUSP_LOCATION = "cuspLocation";
/*     */   public static final String REMARK = "remark";
/*     */   public static final String VALID_DATE = "validDate";
/*     */   public static final String RECORD_USER = "recordUser";
/*     */   public static final String CHARACTER = "character";
/*     */   public static final String POSITION_SITUATION = "positionSituation";
/*     */   public static final String DRESSING = "dressing";
/*     */   public static final String H_SITUATION = "h_situation";
/*     */   public static final String TAPE = "tape";
/*     */   public static final String COLOR = "color";
/*     */   public static final String DEPTH = "depth";
/*     */   public static final String ARM_CIRCUMFERENCE = "armCircumference";
/*     */   public static final String THIGH_CIRCUMFERENCE = "thighCircumference";
/*     */   public static final String TUBE_STATUS = "tubeStatus";
/*     */   public static final String LOCATION = "location";
/*     */   public static final String INFECT = "infect";
/*     */   public static final String INSERT_LENGTH = "insertLength";
/*     */   public static final String EXPOSURE_LENGTH = "exposureLength";
/*     */   public static final String AIR_PRESSURE = "airPressure";
/*     */   public static final String CATHETER_NURSE = "catheterNurse";
/*     */   public static final String SUB_GLOTTIS_ATTRACT = "subGlottisAttract";
/*     */   public static final String HUMIDIFIER_TYPE = "humidifierType";
/*     */   public static final String BAG_PRESSURE = "bagPressure";
/*     */   public static final String CATHETER_CULTURE = "catheterCulture";
/*     */   public static final String HIP_CIRCUMFERENCE = "hipCircumference";
/*     */   public static final String BLOOD_LEVEL = "bloodLevel";
/*     */   public static final String PROTECTIVE_FILM_COMPLETION = "protectiveFilmCompletion";
/*     */   public static final String DRAINAGE_WAY = "drainageWay";
/*     */   public static final String WATER_WAVE = "waterWave";
/*     */   public static final String BUBBLE_OVERFLOW = "bubbleOverflow";
/*     */   public static final String HEPARIN_SOLUTION_TUBE = "heparinSolutionTube";
/*     */   public static final String WASH_PIPE_BRINE = "washPipeBrine";
/*     */   public static final String UNOBSTRUCTED = "unobstructed";
/*     */   public static final String CHANGE_DRESSING = "changeDressing";
/*     */   public static final String PIERCING_HOLE = "piercingHole";
/*     */   public static final String CREATE_WAY = "createWay";
/*     */   public static final String LENGTH_FROM_INCISORS = "lengthFromIncisors";
/*     */   public static final String DRAINAGE_CONTENTS = "drainageContents";
/*     */   public static final String RINSE = "rinse";
/*     */   public static final String CHECK_RESIDUE = "checkResidue";
/*     */   public static final String TUBE_OTHER = "other";
/*     */   public static final String ZERO = "zero";
/*     */   public static final String DRAINAGE_NATURE = "drainageNature";
/*     */   public static final String USAGE = "usage";
/*  77 */   public static final String[] TUBE_CODES = new String[] { "tube_name", "body", "tubeLocation", "size", "casingSize", "material", "urineBagType", "remark" };
/*     */ 
/*     */ 
/*     */ 
/*     */   
/*  82 */   public static final String[] TUBE_ACTION_CODES = new String[] { "character", "positionSituation", "dressing", "h_situation", "tape", "color", "depth", "armCircumference", "thighCircumference", "tubeStatus", "location", "infect", "insertLength", "exposureLength", "airPressure", "catheterNurse", "subGlottisAttract", "humidifierType", "bagPressure", "catheterCulture", "hipCircumference", "bloodLevel", "protectiveFilmCompletion", "drainageWay", "waterWave", "bubbleOverflow", "heparinSolutionTube", "washPipeBrine", "unobstructed", "changeDressing", "piercingHole", "createWay", "lengthFromIncisors", "drainageContents", "rinse", "checkResidue", "zero", "drainageNature" };
/*     */ 
/*     */ 
/*     */   
/*     */   public static final String INPUT = "输入框";
/*     */ 
/*     */ 
/*     */   
/*     */   public static final String COMBOBOX = "下拉框";
/*     */ 
/*     */ 
/*     */   
/*  94 */   public static Map<String, String> mapTubeCode2Name = new HashMap<>();
/*  95 */   public static Map<String, String> mapTubeActionCode2Name = new HashMap<>();
/*     */   
/*     */   static {
/*  98 */     mapTubeCode2Name.put("tube_name", "管道名称");
/*  99 */     mapTubeCode2Name.put("body", "插管部位");
/* 100 */     mapTubeCode2Name.put("tubeLocation", "插管地点");
/* 101 */     mapTubeCode2Name.put("size", "型号");
/* 102 */     mapTubeCode2Name.put("casingSize", "套管型号");
/* 103 */     mapTubeCode2Name.put("material", "管道材质");
/* 104 */     mapTubeCode2Name.put("urineBagType", "尿袋类型");
/* 105 */     mapTubeCode2Name.put("remark", "尿管腔数目");
/*     */     
/* 107 */     mapTubeActionCode2Name.put("character", "性状");
/* 108 */     mapTubeActionCode2Name.put("positionSituation", "部位情况");
/* 109 */     mapTubeActionCode2Name.put("dressing", "敷料类型");
/* 110 */     mapTubeActionCode2Name.put("h_situation", "回血情况");
/* 111 */     mapTubeActionCode2Name.put("tape", "系带");
/* 112 */     mapTubeActionCode2Name.put("color", "颜色");
/* 113 */     mapTubeActionCode2Name.put("depth", "深度");
/* 114 */     mapTubeActionCode2Name.put("armCircumference", "臂围");
/* 115 */     mapTubeActionCode2Name.put("thighCircumference", "大腿围");
/* 116 */     mapTubeActionCode2Name.put("tubeStatus", "状态");
/* 117 */     mapTubeActionCode2Name.put("location", "位置");
/* 118 */     mapTubeActionCode2Name.put("infect", "部位感染");
/* 119 */     mapTubeActionCode2Name.put("insertLength", "置入长度");
/* 120 */     mapTubeActionCode2Name.put("exposureLength", "外露长度");
/* 121 */     mapTubeActionCode2Name.put("airPressure", "气囊压力");
/* 122 */     mapTubeActionCode2Name.put("catheterNurse", "导管护理");
/* 123 */     mapTubeActionCode2Name.put("subGlottisAttract", "声门下吸引");
/* 124 */     mapTubeActionCode2Name.put("humidifierType", "湿化器类型");
/* 125 */     mapTubeActionCode2Name.put("bagPressure", "加压袋压力");
/* 126 */     mapTubeActionCode2Name.put("catheterCulture", "导管头培养");
/* 127 */     mapTubeActionCode2Name.put("hipCircumference", "臀围");
/* 128 */     mapTubeActionCode2Name.put("bloodLevel", "透析器凝血分级");
/* 129 */     mapTubeActionCode2Name.put("protectiveFilmCompletion", "保护膜完成");
/* 130 */     mapTubeActionCode2Name.put("drainageWay", "引流方式");
/* 131 */     mapTubeActionCode2Name.put("waterWave", "水柱波动");
/* 132 */     mapTubeActionCode2Name.put("bubbleOverflow", "气泡溢出");
/* 133 */     mapTubeActionCode2Name.put("heparinSolutionTube", "肝素钠溶液封管");
/* 134 */     mapTubeActionCode2Name.put("washPipeBrine", "冲管盐水");
/* 135 */     mapTubeActionCode2Name.put("unobstructed", "通畅");
/* 136 */     mapTubeActionCode2Name.put("changeDressing", "更换敷料");
/* 137 */     mapTubeActionCode2Name.put("piercingHole", "穿刺口");
/* 138 */     mapTubeActionCode2Name.put("createWay", "置管方式");
/* 139 */     mapTubeActionCode2Name.put("lengthFromIncisors", "距门齿长度");
/* 140 */     mapTubeActionCode2Name.put("drainageContents", "引流液内容物");
/* 141 */     mapTubeActionCode2Name.put("rinse", "冲洗");
/* 142 */     mapTubeActionCode2Name.put("checkResidue", "检查残留量");
/* 143 */     mapTubeActionCode2Name.put("zero", "归零");
/* 144 */     mapTubeActionCode2Name.put("drainageNature", "引流液性质");
/* 145 */     mapTubeActionCode2Name.put("other", "其他");
/*     */   }
/*     */ }


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\pojo\tubeExe\TubeConfigConstant.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
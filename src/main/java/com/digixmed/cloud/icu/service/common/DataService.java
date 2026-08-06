package com.digixmed.cloud.icu.service.common;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.digixmed.cloud.icu.dao.MongoDao;
import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.service.*;


import com.digixmed.cloud.icu.util.DataUtils;
import com.digixmed.cloud.icu.util.DateUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Service
public class DataService {
    /*  25 */   private static final Logger log = LoggerFactory.getLogger(DataService.class);

    @Autowired
    MongoDao mongoDao;

    @Autowired
    TemperatureServiceImpl temperatureServiceImpl;

    @Autowired
    PulseServiceImpl pulseServiceImpl;

    @Autowired
    DefaultServiceImpl defaultServiceImpl;

    @Autowired
    IBPServiceImpl ibpServiceImpl;

    @Autowired
    CRLServiceImpl crlServiceImpl;

    @Autowired
    UrinateServiceImpl urinateServiceImpl;

    @Autowired
    FaecesServiceImpl faecesServiceImpl;

    @Autowired
    FaecesCountServiceImpl faecesCountService;

    @Autowired
    BreatheServiceImpl breatheServiceImpl;

    @Autowired
    BedsideConfigDBService bedsideConfigDBService;


    public void deleteMessage(int days) {
        /*  62 */
        DateTime dateTime = DateUtil.offsetDay(new Date(), -days);
        /*  63 */
        this.mongoDao.deleteMessageLog((Date) dateTime);
    }

    public boolean selectOneVitalSign(String id) {
        /*  67 */
        Document bedSide = this.mongoDao.selectBedSideById(id);
        /*  68 */
        return this.defaultServiceImpl.updateAndSaveIntermediateTable(filterByCode(bedSide, Boolean.valueOf(false))).booleanValue();
    }


    public void selectVitalSignsAfterLastTime(Date lastTime) {
        /*  73 */
        DateTime dateTime = DateUtil.offsetDay(lastTime, -1);
        /*  74 */
        List<Document> docs = this.mongoDao.selectVitalSignsAfterLastTime((Date) dateTime, MyConfig.CODES);
        /*  75 */
        List<Document> bedside = (List<Document>) docs.stream().filter(doc -> DateUtils.isEqual(getValueFromDocByKey(doc, "time", Date.class), Boolean.valueOf(false)).booleanValue()).collect(Collectors.toList());

        /*  77 */
        List<Document> xlDocs = this.mongoDao.selectVitalSignsAfterLastTime((Date) dateTime, MyConfig.XLCODES);
        /*  78 */
        if (xlDocs != null && xlDocs.size() != 0) {
            /*  79 */
            List<Document> xlBedside = (List<Document>) xlDocs.stream().filter(doc -> DateUtils.isEqual(getValueFromDocByKey(doc, "time", Date.class), Boolean.valueOf(false)).booleanValue()).collect(Collectors.toList());
            /*  80 */
            if (xlBedside != null && xlBedside.size() != 0) {
                /*  81 */
                for (Document document : xlBedside) {
                    /*  82 */
                    if ("房颤".equals(DataUtils.getValueFromDocByKey(document, "strVal", String.class))) {
                        /*  83 */
                        String pid = (String) DataUtils.getValueFromDocByKey(document, "pid", String.class);
                        /*  84 */
                        List<Document> mbDocs = this.mongoDao.selectVitalSignsAfterLastTime((Date) dateTime, "param_PR", pid);
                        /*  85 */
                        if (mbDocs != null && mbDocs.size() != 0) {
                            /*  86 */
                            List<Document> mbBedside = (List<Document>) mbDocs.stream().filter(doc -> DateUtils.isEqual(getValueFromDocByKey(doc, "time", Date.class), Boolean.valueOf(false)).booleanValue()).collect(Collectors.toList());
                            /*  87 */
                            if (mbBedside != null && mbBedside.size() != 0) {
                                /*  88 */
                                bedside.addAll(mbBedside);
                            }
                        }
                    }
                }
            }
        }
        /*  95 */
        bedside.stream().forEach(doc -> this.defaultServiceImpl.updateAndSaveIntermediateTable(filterByCode(doc, Boolean.valueOf(false))));
    }


    public void selectVitalSignsAfterLastTimeXY(Date lastTime) {
        /* 102 */
        List<Document> xydocs = this.mongoDao.selectVitalSignsAfterLastTime(lastTime, MyConfig.XYCODES);
        /* 103 */
        List<Document> bedside = (List<Document>) xydocs.stream().filter(doc -> DateUtils.isEqual(getValueFromDocByKey(doc, "time", Date.class), Boolean.valueOf(true)).booleanValue()).collect(Collectors.toList());
        /* 104 */
        bedside.stream().forEach(doc -> this.defaultServiceImpl.updateAndSaveIntermediateTable(filterByCode(doc, Boolean.valueOf(false))));
    }


    public void selectCRLiangAfterLastTime_new(Date lastTime, Boolean isIn, Integer backDay, Integer timePointasd) {
        /* 116 */
        List<Document> crlDocs = new ArrayList<>();
        /* 117 */
        List<String> pids = new ArrayList<>();
        /* 118 */
        String codeName = isIn.booleanValue() ? "param_in_hour_sum" : "param_out_hour_sum";

        /* 120 */
        List<Document> allDocsTemp = new ArrayList<>();

        /* 122 */
        Date timeByOclock = DateUtils.formatTimePoint(lastTime, timePointasd.intValue());
        /* 123 */
        Date startTime = DateUtils.getYesterDayJian(timeByOclock, backDay);
        /* 124 */
        Date endTime = timeByOclock;
        /* 125 */
        log.info("统计" + codeName + "从" + DateUtil.format(startTime, "yyyy-MM-dd") + "到" + DateUtil.format(endTime, "yyyy-MM-dd") + "在bedside表里面的数据,向前追溯的时间为：" + backDay + "天");
        /* 126 */
        allDocsTemp.addAll(this.mongoDao.selectAllSignsBetweenTime(startTime, endTime));


        /* 129 */
        Map<String, List<Document>> patMapDoc = new HashMap<>();
        /* 130 */
        List<Document> tempDocsTemp = List.copyOf(allDocsTemp);
        /* 131 */
        List<Document> crlDocsTemp = new ArrayList<>();
        /* 132 */
        tempDocsTemp.stream().forEach(doc -> {
            Date date = DateUtils.formatTimePoint(getValueFromDocByKey(doc, "time", Date.class), timePointasd.intValue());

            String pid = getValueFromDocByKey(doc, "pid", String.class);

            String pidDate = StrUtil.format("{}_{}", new Object[]{pid, DateUtil.format(date, "yyyyMMddHmmss")});

            if (patMapDoc.containsKey(pidDate)) {
                ((List<Document>) patMapDoc.get(pidDate)).add(doc);
            } else {
                List<Document> Lists = new ArrayList<>();
                Lists.add(doc);
                patMapDoc.put(pidDate, Lists);
            }
        });
        /* 147 */
        for (Map.Entry<String, List<Document>> stringListEntry : patMapDoc.entrySet()) {
            /* 148 */
            String[] pd = ((String) stringListEntry.getKey()).split("_");

            /* 150 */
            List<String> tempCodes = this.bedsideConfigDBService.getInOutBedSideCode(pd[0], (Date) DateUtil.parse(pd[1], "yyyyMMddHmmss"), isIn);
            /* 151 */
            List<Document> docItem = stringListEntry.getValue();
            /* 152 */
            for (Document document : docItem) {
                /* 153 */
                if (ObjectUtil.isNotEmpty(tempCodes) && tempCodes.contains(getValueFromDocByKey(document, "code", String.class))) {
                    /* 154 */
                    document.put("clCodes", tempCodes);

                    /* 156 */
                    document.put("code", codeName);
                    /* 157 */
                    document.put("time", DateUtils.formatTimePoint(getValueFromDocByKey(document, "time", Date.class), timePointasd.intValue()));
                    /* 158 */
                    crlDocsTemp.add(document);
                }
            }
        }

        /* 163 */
        if (backDay.intValue() == 1) {

            /* 165 */
            Date todayTimePoint = DateUtils.formatTimePoint(new Date(), timePointasd.intValue());

            /* 167 */
            crlDocsTemp = (List<Document>) crlDocsTemp.stream().filter(crlDoc -> !todayTimePoint.equals(getValueFromDocByKey(crlDoc, "time", Date.class))).collect(Collectors.toList());
        }

        /* 170 */
        List<Document> pid1 = (List<Document>) crlDocsTemp.stream().filter(DataUtils.distinctByKey(crlDoc -> getValueFromDocByKey(crlDoc, "pid", String.class))).collect(Collectors.toList());
        /* 171 */
        pid1.stream().forEach(patientDoc -> pids.add(getValueFromDocByKey(patientDoc, "pid", String.class)));
        /* 172 */
        for (String pid : pids) {
            try {
                /* 174 */
                List<Document> tempWithPid = (List<Document>) crlDocsTemp.stream().filter(crlDoc -> pid.equals(getValueFromDocByKey(crlDoc, "pid", String.class))).collect(Collectors.toList());
                /* 175 */
                List<Document> tempWithTime = (List<Document>) tempWithPid.stream().filter(DataUtils.distinctByKey(crlDocTime -> getValueFromDocByKey(crlDocTime, "time", Date.class))).collect(Collectors.toList());
                /* 176 */
                if (tempWithTime.size() != 0) {
                    /* 177 */
                    crlDocs.addAll(tempWithTime);
                }
                /* 179 */
            } catch (Exception e) {
                /* 180 */
                String isInStr = isIn.booleanValue() ? "累计入量" : "累计出量";
                /* 181 */
                log.error("病人：" + pid + "记录的" + isInStr + "统计报错：" + e);
            }
        }

        /* 185 */
        crlDocs.stream().forEach(doc -> this.defaultServiceImpl.updateAndSaveIntermediateTable(filterByCode(doc, Boolean.valueOf(false))));
    }


    public void selectChuLiangOtherAfterLastTime_new(Date lastTime, Integer backDay, Integer timePointasd) {
        /* 195 */
        List<Document> crlDocs = new ArrayList<>();
        /* 196 */
        List<String> pids = new ArrayList<>();

        /* 198 */
        String codeName = "param_out_other";

        /* 200 */
        List<Document> allDocsTemp = new ArrayList<>();


        /* 203 */
        Date timeByOclock = DateUtils.formatTimePoint(lastTime, timePointasd.intValue());
        /* 204 */
        Date startTime = DateUtils.getYesterDayJian(timeByOclock, backDay);
        /* 205 */
        Date endTime = timeByOclock;
        /* 206 */
        log.info("统计" + codeName + "从" + DateUtil.format(startTime, "yyyy-MM-dd") + "到" + DateUtil.format(endTime, "yyyy-MM-dd") + "在bedside表里面的数据,向前追溯的时间为：" + backDay + "天");
        /* 207 */
        allDocsTemp.addAll(this.mongoDao.selectAllSignsBetweenTime(startTime, endTime));


        /* 210 */
        Map<String, List<Document>> patMapDoc = new HashMap<>();
        /* 211 */
        List<Document> tempDocsTemp = List.copyOf(allDocsTemp);
        /* 212 */
        List<Document> crlDocsTemp = new ArrayList<>();
        /* 213 */
        tempDocsTemp.stream().forEach(doc -> {
            Date date = DateUtils.formatTimePoint(getValueFromDocByKey(doc, "time", Date.class), timePointasd.intValue());

            String pid = getValueFromDocByKey(doc, "pid", String.class);
            String pidDate = StrUtil.format("{}_{}", new Object[]{pid, DateUtil.format(date, "yyyyMMddHmmss")});
            if (patMapDoc.containsKey(pidDate)) {
                ((List<Document>) patMapDoc.get(pidDate)).add(doc);
            } else {
                List<Document> Lists = new ArrayList<>();
                Lists.add(doc);
                patMapDoc.put(pidDate, Lists);
            }
        });
        /* 226 */
        for (Map.Entry<String, List<Document>> stringListEntry : patMapDoc.entrySet()) {
            /* 227 */
            String[] pd = ((String) stringListEntry.getKey()).split("_");

            /* 229 */
            List<String> tempCodes = this.bedsideConfigDBService.getInOutBedSideCode(pd[0], (Date) DateUtil.parse(pd[1], "yyyyMMddHmmss"), Boolean.valueOf(false));
            /* 230 */
            if (ObjectUtil.isEmpty(tempCodes)) {
                continue;
            }

            /* 234 */
            tempCodes.remove("param_niaoLiang");
            /* 235 */
            List<Document> docItem = stringListEntry.getValue();
            /* 236 */
            for (Document document : docItem) {
                /* 237 */
                if (ObjectUtil.isNotEmpty(tempCodes) && tempCodes.contains(getValueFromDocByKey(document, "code", String.class))) {
                    /* 238 */
                    document.put("clCodes", tempCodes);

                    /* 240 */
                    document.put("code", codeName);
                    /* 241 */
                    document.put("time", DateUtils.formatTimePoint(getValueFromDocByKey(document, "time", Date.class), timePointasd.intValue()));
                    /* 242 */
                    crlDocsTemp.add(document);
                }
            }
        }

        /* 247 */
        if (backDay.intValue() == 1) {

            /* 249 */
            Date todayTimePoint = DateUtils.formatTimePoint(new Date(), timePointasd.intValue());

            /* 251 */
            crlDocsTemp = (List<Document>) crlDocsTemp.stream().filter(crlDoc -> !todayTimePoint.equals(getValueFromDocByKey(crlDoc, "time", Date.class))).collect(Collectors.toList());
        }

        /* 254 */
        List<Document> pid1 = (List<Document>) crlDocsTemp.stream().filter(DataUtils.distinctByKey(crlDoc -> getValueFromDocByKey(crlDoc, "pid", String.class))).collect(Collectors.toList());
        /* 255 */
        pid1.stream().forEach(patientDoc -> pids.add(getValueFromDocByKey(patientDoc, "pid", String.class)));
        /* 256 */
        for (String pid : pids) {
            try {
                /* 258 */
                List<Document> tempWithPid = (List<Document>) crlDocsTemp.stream().filter(crlDoc -> pid.equals(getValueFromDocByKey(crlDoc, "pid", String.class))).collect(Collectors.toList());
                /* 259 */
                List<Document> tempWithTime = (List<Document>) tempWithPid.stream().filter(DataUtils.distinctByKey(crlDocTime -> getValueFromDocByKey(crlDocTime, "time", Date.class))).collect(Collectors.toList());
                /* 260 */
                if (tempWithTime.size() != 0) {
                    /* 261 */
                    crlDocs.addAll(tempWithTime);
                }
                /* 263 */
            } catch (Exception e) {
                /* 264 */
                String isInStr = "累计出_其他";
                /* 265 */
                log.error("病人：" + pid + "记录的" + isInStr + "统计报错：" + e);
            }
        }

        /* 269 */
        crlDocs.stream().forEach(doc -> this.defaultServiceImpl.updateAndSaveIntermediateTable(filterByCode(doc, Boolean.valueOf(false))));
    }


    public void selectDXLiangAfterLastTime(Date lastTime, Integer backDay, Integer timePointasd) {
        /* 280 */
        for (String code : MyConfig.DXCODES) {
            try {
                /* 282 */
                List<Document> crlDocs = new ArrayList<>();
                /* 283 */
                List<String> pids = new ArrayList<>();
                /* 284 */
                boolean shit = "param_daBianAmount".equals(code);
                /* 285 */
                String[] codes = shit ? MyConfig.DBCODES : MyConfig.NLCODES;
                /* 286 */
                List<Document> crlDocsTemp = new ArrayList<>();
                /* 287 */
                if (shit) {

                    /* 289 */
                    Date timeByOclock = DateUtils.formatTimePoint(lastTime, 15);
                    /* 290 */
                    Date startTime = DateUtils.getYesterDayJian(timeByOclock, backDay);
                    /* 291 */
                    Date endTime = timeByOclock;
                    /* 292 */
                    log.info("统计大便从" + DateUtil.format(startTime, "yyyy-MM-dd") + "到" + DateUtil.format(endTime, "yyyy-MM-dd") + "在bedside表里面的数据,向前追溯的时间为：" + backDay + "天");
                    /* 293 */
                    crlDocsTemp.addAll(this.mongoDao.selectCRSignsBetweenTime(startTime, endTime, codes));
                } else {

                    /* 296 */
                    Date timeByOclock = DateUtils.formatTimePoint(lastTime, timePointasd.intValue());
                    /* 297 */
                    Date startTime = DateUtils.getYesterDayJian(timeByOclock, backDay);
                    /* 298 */
                    Date endTime = timeByOclock;
                    /* 299 */
                    log.info("统计小便从" + DateUtil.format(startTime, "yyyy-MM-dd") + "到" + DateUtil.format(endTime, "yyyy-MM-dd") + "在bedside表里面的数据,向前追溯的时间为：" + backDay + "天");
                    /* 300 */
                    crlDocsTemp.addAll(this.mongoDao.selectCRSignsBetweenTime(startTime, endTime, codes));
                }

                /* 303 */
                crlDocsTemp.stream().forEach(crlDoc -> {
                    if (shit) {
                        crlDoc.put("time", DateUtils.formatTimePoint(getValueFromDocByKey(crlDoc, "time", Date.class), 15));
                    } else {
                        crlDoc.put("time", DateUtils.formatTimePoint(getValueFromDocByKey(crlDoc, "time", Date.class), timePointasd.intValue()));
                    }
                });




                /* 314 */
                if (backDay.intValue() == 1) {
                    /* 315 */
                    if (shit) {

                        /* 317 */
                        Date todayTimePoint = DateUtils.formatTimePoint(new Date(), 15);

                        /* 319 */
                        crlDocsTemp = (List<Document>) crlDocsTemp.stream().filter(crlDoc -> !todayTimePoint.equals(getValueFromDocByKey(crlDoc, "time", Date.class))).collect(Collectors.toList());
                    } else {

                        /* 322 */
                        Date todayTimePoint = DateUtils.formatTimePoint(new Date(), timePointasd.intValue());

                        /* 324 */
                        crlDocsTemp = (List<Document>) crlDocsTemp.stream().filter(crlDoc -> !todayTimePoint.equals(getValueFromDocByKey(crlDoc, "time", Date.class))).collect(Collectors.toList());
                    }
                }

                /* 328 */
                List<Document> pid1 = (List<Document>) crlDocsTemp.stream().filter(DataUtils.distinctByKey(crlDoc -> getValueFromDocByKey(crlDoc, "pid", String.class))).collect(Collectors.toList());
                /* 329 */
                pid1.stream().forEach(patientDoc -> pids.add(getValueFromDocByKey(patientDoc, "pid", String.class)));

                /* 331 */
                Map<String, Date> pidWithAddmitTime = new HashMap<>();

                /* 333 */
                pids.stream().forEach(pid -> pidWithAddmitTime.put(pid, this.mongoDao.selectPatientByPid(pid).getDate("icuAdmissionTime")));
                /* 334 */
                for (String pid : pids) {
                    try {
                        /* 336 */
                        List<Document> tempWithPid = (List<Document>) crlDocsTemp.stream().filter(crlDoc -> pid.equals(getValueFromDocByKey(crlDoc, "pid", String.class))).collect(Collectors.toList());
                        /* 337 */
                        List<Document> tempWithTime = (List<Document>) tempWithPid.stream().filter(DataUtils.distinctByKey(crlDocTime -> getValueFromDocByKey(crlDocTime, "time", Date.class))).collect(Collectors.toList());
                        /* 338 */
                        if (tempWithTime.size() != 0) {
                            /* 339 */
                            crlDocs.addAll(tempWithTime);
                        }
                        /* 341 */
                    } catch (Exception e) {
                        /* 342 */
                        log.error("病人：" + pid + "的" + code + "统计大小便入量报错：" + e);
                    }
                }

                /* 346 */
                crlDocs.stream().forEach(doc -> {
                    IntermediateTable intermediateTable = filterByCode(doc, Boolean.valueOf(false));

                    if (intermediateTable != null) {
                        String pid = getValueFromDocByKey(doc, "pid", String.class);

                        Date addmitTime = DateUtils.formatTimePoint((Date) pidWithAddmitTime.get(pid), timePointasd.intValue());

                        if (addmitTime != null && addmitTime.equals(intermediateTable.getTimePoint())) {
                            intermediateTable.setIsFirst(Boolean.valueOf(true));
                        }

                        if (shit && intermediateTable.getChlidList() != null) {
                            int size = intermediateTable.getChlidList().size();

                            intermediateTable.setSignValue(Convert.toStr(Integer.valueOf(size), "0"));
                        }
                    }

                    this.crlServiceImpl.updateAndSaveIntermediateTable(intermediateTable);
                });
                /* 367 */
            } catch (Exception e) {
                /* 368 */
                log.error(code + "统计大小便入量报错：" + code);
            }
        }
    }


    public Boolean isUrinaryTube(String pid, Date leftTime) {
        /* 375 */
        return Boolean.valueOf((pid != null && leftTime != null) ? this.urinateServiceImpl.isUrinaryTube(pid, leftTime).booleanValue() : false);
    }


    public Document queryPatientByPid(String pid) {
        /* 384 */
        return this.mongoDao.selectPatientByPid(pid);
    }

    public void selectPatientsInDepts() {
        /* 388 */
        List<Document> patients = this.mongoDao.selectAdmittedPatients();
        /* 389 */
        for (String code : MyConfig.CODES) {
            /* 390 */
            for (Document patient : patients) {
                /* 391 */
                String pid = ((ObjectId) getValueFromDocByKey(patient, "_id", ObjectId.class)).toHexString();

                try {
                    /* 394 */
                    Date icuAdmissionTime = getValueFromDocByKey(patient, "icuAdmissionTime", Date.class);
                    /* 395 */
                    Document firstBedSide = this.mongoDao.selectBedSideByTimeAndIdAndCode(pid, code, icuAdmissionTime);

                    /* 397 */
                    IntermediateTable table = filterByCode(firstBedSide, Boolean.TRUE);

                    /* 399 */
                    this.defaultServiceImpl.updateAndSaveIntermediateTable(table);
                    /* 400 */
                } catch (Exception e) {
                    /* 401 */
                    log.error("病人：" + pid + "记录第一天的" + code + "统计报错：" + e.toString());
                }
            }
        }
    }

    public IntermediateTable filterByCode(Document doc, Boolean isFirst) {
        TemperatureServiceImpl temperatureServiceImpl;
        PulseServiceImpl pulseServiceImpl;
        IBPServiceImpl iBPServiceImpl;
        CRLServiceImpl cRLServiceImpl;
        UrinateServiceImpl urinateServiceImpl;
        FaecesCountServiceImpl faecesCountServiceImpl;
        BreatheServiceImpl breatheServiceImpl;
        DefaultServiceImpl defaultServiceImpl=new DefaultServiceImpl();
        /* 409 */
        if (doc == null) {
            /* 410 */
            return null;
        }
        /* 412 */
        BaseService service = null;
        /* 413 */
        IntermediateTable intermediateTable = null;
        /* 414 */
        String code = getValueFromDocByKey(doc, "code", String.class);
        /* 415 */
        String bedsideId = ((ObjectId) getValueFromDocByKey(doc, "_id", ObjectId.class)).toString();
        /* 416 */
        if (code == null) {
            /* 417 */
            return null;
        }
        /* 419 */
        switch (code) {
            case "param_T":
                /* 421 */
                temperatureServiceImpl = this.temperatureServiceImpl;
                break;
            case "param_PR":
                /* 424 */
                pulseServiceImpl = this.pulseServiceImpl;
                break;
            case "param_nibp_d":
            case "param_ibp_d":
                /* 428 */
                iBPServiceImpl = this.ibpServiceImpl;
                break;
            case "param_out_hour_sum":
            case "param_out_other":
            case "param_in_hour_sum":
                /* 433 */
                cRLServiceImpl = this.crlServiceImpl;
                break;
            case "param_niaoLiang":
                /* 436 */
                urinateServiceImpl = this.urinateServiceImpl;
                break;
            case "param_daBianAmount":
            case "param_daBianCiShu":
                /* 440 */
                faecesCountServiceImpl = this.faecesCountService;
                break;
            case "param_resp":
                /* 443 */
                breatheServiceImpl = this.breatheServiceImpl;
                break;
            case "param_HR":
                /* 446 */
                defaultServiceImpl = this.defaultServiceImpl;
                break;
        }

        /* 450 */
        if (defaultServiceImpl != null) {
            try {
                /* 452 */
                intermediateTable = isFirst.booleanValue() ? defaultServiceImpl.handle4First(doc) : defaultServiceImpl.handle(doc);
                /* 453 */
            } catch (Exception e) {
                /* 454 */
                log.error("filterByCode() -->bedSide记录：{} , {} 的体征, 统计报错:{},e:{}", new Object[]{bedsideId, code, e.getMessage(), e.toString()});
                /* 455 */
                intermediateTable = null;
            }
        }
        /* 458 */
        return intermediateTable;
    }


    public <T> T getValueFromDocByKey(Document doc, String path, Class<T> clazz) {
        /* 463 */
        if (doc == null) {
            /* 464 */
            return null;
        }
        /* 466 */
        Object result = null;
        try {
            /* 468 */
            if (path.contains(".")) {
                /* 469 */
                String[] keys = path.split("\\.");
                /* 470 */
                for (String key : keys) {
                    /* 471 */
                    if (doc.get(key) == null) {
                        break;
                    }
                    /* 474 */
                    if (doc.get(key) instanceof Document) {
                        /* 475 */
                        doc = (Document) doc.get(key, Document.class);
                    } else {
                        /* 477 */
                        result = doc.get(key, clazz);
                    }
                }
            } else {
                /* 481 */
                result = doc.get(path, clazz);
            }

            /* 484 */
        } catch (Exception e) {
            /* 485 */
            log.error(path + "报错：" + path);
        }
        /* 487 */
        if (result != null) {
            /* 488 */
            if (result instanceof String && (
                    /* 489 */         (String) result).isEmpty()) {
                /* 490 */
                return null;
            }

            /* 493 */
            return (T) result;
        }
        /* 495 */
        return null;
    }
}


/* Location:              E:\深医\医院\重钢医院\接口对接资料\体温单\lp-temperature-service-1.0.0.jar!\com\digixmed\cloud\icu\service\common\DataService.class
 * Java compiler version: 13 (57.0)
 * JD-Core Version:       1.1.3
 */
package com.digixmed.cloud.icu.service.common;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.digixmed.cloud.icu.dao.MongoDao;
import com.digixmed.cloud.icu.pojo.Data;
import com.digixmed.cloud.icu.pojo.DataValue;
import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.util.DataUtils;
import com.digixmed.cloud.icu.util.HttpUtils;
import com.digixmed.cloud.icu.util.XMLUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

@Service
@RefreshScope
public class ReturnService {
    /*  29 */   private static final Logger log = LoggerFactory.getLogger(ReturnService.class);


    @Resource
    MongoDao mongoDao;


    @Value("${digixmed.url}")
    public String url;

    @Value("${digixmed.timePointasd}")
    public Integer timePointasd;

    @Value("${digixmed.debug}")
    public Boolean debug;

    @Value("${digixmed.isUpload}")
    public Boolean isUpload;

    @Value("${digixmed.testPid}")
    public String testPid;


    public void uploadInfo() {
        /*  53 */
        Date now = new Date();
        /*  54 */
        if (this.isUpload.booleanValue()) {
            /*  55 */
            log.error(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "进行回传");
            /*  56 */
            log.error("url:" + this.url + "---isUpload:" + this.isUpload + "-----testPid" + this.testPid);
            /*  57 */
            int count = 0;
            /*  58 */
            List<IntermediateTable> tableInfos = new ArrayList<>();
            /*  59 */
            if ("all".equals(this.testPid)) {
                /*  60 */
                tableInfos = this.mongoDao.selectNoUploadInfo();
                /*  61 */
            } else if (this.testPid != null) {
                /*  62 */
                String[] split = this.testPid.split(",");
                /*  63 */
                tableInfos = this.mongoDao.selectNoUploadInfo(split);
            }
            /*  65 */
            if (tableInfos.size() == 0) {
                /*  66 */
                log.error(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "--tableInfos为空");

                return;
            }
            /*  70 */
            for (IntermediateTable tableInfo : tableInfos) {
                /*  71 */
                Data data = new Data();
                /*  72 */
                List<DataValue> dataValues = new ArrayList<>();

                /*  74 */
                if (!this.debug.booleanValue() &&
                        /*  75 */           tableInfo.getTimePoint() != null && tableInfo.getTimePoint().after(now)) {
                    /*  76 */
                    log.info("记录时间不得超过当前时间!当前时间:{},记录时间:{},记录:{},", new Object[]{DateUtil.format(now, "yyyy-MM-dd HH:mm:ss"), DateUtil.format(tableInfo.getTimePoint(), "yyyy-MM-dd HH:mm:ss"), tableInfo.toString()});

                    continue;
                }
                /*  80 */
                String patientId = tableInfo.getPatientId();


                /*  83 */
                Boolean flag = Boolean.valueOf(false);
                /*  84 */
                for (String code : MyConfig.CODES) {
                    /*  85 */
                    if (!code.equals(tableInfo.getSignCode())) {
                        /*  87 */
                        if (ObjectUtil.isNotEmpty(patientId)) {

                            /*  89 */
                            Document document = this.mongoDao.selectPatientWaitDischarged(tableInfo.getPid());

                            /*  91 */
                            Document document2 = this.mongoDao.selectPatientDischarged(tableInfo.getPid());
                            /*  92 */
                            if (ObjectUtil.isNotEmpty(document) || ObjectUtil.isNotEmpty(document2)) {
                                /*  93 */
                                log.info(tableInfo.getPid() + "病人待出科或者出科状态出入量不回传");
                                /*  94 */
                                flag = Boolean.valueOf(true);
                            }
                        }
                    }
                }


                /* 101 */
                if (flag.booleanValue()) {
                    /* 102 */
                    log.error(patientId + "病人待出科或者出科状态出入量不回传");

                    continue;
                }

                /* 107 */
                DataValue dataValue = tableToData(tableInfo);
                /* 108 */
                if (data != null) {
                    /* 109 */
                    dataValues.add(dataValue);
                }
                /* 111 */
                data.setData(dataValues);

                /* 113 */
                String dataStr = XMLUtils.convertToXml(data);
                /* 114 */
                String requestStr = DataUtils.getRequestStr(dataStr);
                /* 115 */
                if (this.isUpload.booleanValue()) {
                    /* 116 */
                    log.error(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "开始组装回传");

                    /* 118 */
                    Map<String, String> resultMap = upload(requestStr);
                    /* 119 */
                    String msg = resultMap.get("msg");
                    /* 120 */
                    log.info("请求的报文为:" + dataStr + ",响应的报文为:" + msg);
                    /* 121 */
                    tableInfo.setRequestMsg(dataStr);
                    /* 122 */
                    tableInfo.setReponseMsg(msg);

                    /* 124 */
                    tableInfo.setReturnTime(new Date());
                    /* 125 */
                    log.error(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "回传结束");
                    /* 126 */
                    if ("200".equals(resultMap.get("code"))) {
                        /* 127 */
                        if (msg != null && msg.contains("成功")) {
                            /* 128 */
                            updateUploadLog(tableInfo, Boolean.valueOf(true), null);
                            continue;
                        }
                        /* 130 */
                        String errorMsg = StringUtils.substringBetween(msg, "<msg>", "</msg>");
                        /* 131 */
                        updateUploadLog(tableInfo, Boolean.valueOf(false), errorMsg);
                        continue;
                    }
                    /* 134 */
                    updateUploadLog(tableInfo, Boolean.valueOf(false), msg);
                    /* 135 */
                    log.error("回传失败" + dataValues.size() + "条体征记录，" + requestStr);
                    continue;
                }
                /* 138 */
                log.error("回传的配置未打开,待回传的报文为：" + dataStr);
            }

            /* 141 */
            log.info("成功回传了{}病人的{}条体征记录", this.testPid, Integer.valueOf(count));
        } else {
            /* 143 */
            log.error("测试时，不进行回传!");
        }
        /* 145 */
        log.error(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "结束回传");
    }


    public Map<String, String> upload(String dataStr) {
        /* 168 */
        Map<String, String> result = new HashMap<>();
        try {
            /* 170 */
            result = HttpUtils.doPost(this.url, dataStr);
            /* 171 */
        } catch (Exception e) {
            /* 172 */
            result.put("code", "404");
            /* 173 */
            result.put("result", "请求失败");
            /* 174 */
            result.put("success", "false");
            /* 175 */
            result.put("msg", "请求接口失败！原因：" + e.getMessage());
        }
        /* 177 */
        return result;
    }

    public DataValue tableToData(IntermediateTable tableInfo) {
        /* 181 */
        DataValue data = new DataValue();

        try {
            /* 184 */
            String vitalsignType = DataUtils.getSignCodeByCode(tableInfo.getSignCode());
            /* 185 */
            Integer valid = Integer.valueOf(0);

            /* 187 */
            if (tableInfo.getIsValid() != null) {
                /* 188 */
                valid = Integer.valueOf(tableInfo.getIsValid().booleanValue() ? 1 : 0);
            }
            /* 190 */
            data.setIsValid(valid.intValue());
            /* 191 */
            data.setMrn(tableInfo.getMrn());
            /* 192 */
            data.setPatientId(tableInfo.getPatientId());
            /* 193 */
            data.setPatientName(tableInfo.getPatientName());
            /* 194 */
            data.setRecordNurseId(tableInfo.getAuthorId());
            /* 195 */
            data.setRecordNurseName(tableInfo.getAuthorName());
            /* 196 */
            data.setWardCode("2006");
            /* 197 */
            data.setPlanTime(tableInfo.getTimePoint());
            /* 198 */
            data.setRecordTime(tableInfo.getLastEditTime());
            /* 199 */
            data.setRemark("");
            /* 200 */
            data.setSeries(tableInfo.getZycs());
            /* 201 */
            data.setUnit(tableInfo.getSignUnit());
            /* 202 */
            String signValue = tableInfo.getSignValue();


            /* 205 */
            if ("param_niaoLiang".equals(tableInfo.getSignCode())) {
                /* 206 */
                data.setVitalsignSVal1(signValue);
            }
            /* 208 */
            else if (MyConfig.CODES_XUEYA.contains(tableInfo.getSignCode())) {
                /* 209 */
                String[] signValues = signValue.split("/");
                /* 210 */
                data.setVitalsignNVal1(signValues[0]);
                /* 211 */
                data.setVitalsignNVal2(signValues[1]);
                /* 212 */
            } else if ("param_daBianCiShu".contains(tableInfo.getSignCode()) || "param_daBianAmount".contains(tableInfo.getSignCode())) {
                /* 213 */
                data.setVitalsignSVal1(signValue);


            }
            /* 218 */
            else if (MyConfig.CODES[0].contains(tableInfo.getSignCode())) {
                /* 219 */
                data.setVitalsignNVal1(signValue);
                /* 220 */
                data.setVitalsignSVal1("腋温");
                /* 221 */
            } else if (MyConfig.CODES[1].contains(tableInfo.getSignCode())) {
                /* 222 */
                data.setVitalsignNVal1(signValue);
                /* 223 */
            } else if ("param_PR".contains(tableInfo.getSignCode())) {
                /* 224 */
                data.setVitalsignNVal1(signValue);
                /* 225 */
            } else if (MyConfig.CODES[2].contains(tableInfo.getSignCode())) {
                /* 226 */
                data.setVitalsignNVal1(signValue);
                /* 227 */
                if (tableInfo.getInHuXiJi().booleanValue()) {
                    /* 228 */
                    data.setVitalsignSVal1("呼吸机");
                } else {
                    /* 230 */
                    data.setVitalsignSVal1("");
                }
                /* 232 */
            } else if ("param_in_hour_sum".contains(tableInfo.getSignCode()) || "param_out_hour_sum"
/* 233 */.contains(tableInfo.getSignCode()) || "param_out_other"
/* 234 */.contains(tableInfo.getSignCode())) {
                /* 235 */
                data.setVitalsignSVal1(signValue);
            }
            /* 237 */
            data.setVitalsignName(tableInfo.getSignName());
            /* 238 */
            data.setVitalsignType(vitalsignType);
            /* 239 */
        } catch (Exception e) {
            /* 240 */
            log.error("{},转换报错：{}", tableInfo.toString(), e.toString());
        }
        /* 242 */
        return data;
    }


    public void updateUploadLog(IntermediateTable tableInfo, Boolean isSuccess, String error) {
        /* 248 */
        this.mongoDao.updateSuccessLog(tableInfo, isSuccess, error);
    }
}


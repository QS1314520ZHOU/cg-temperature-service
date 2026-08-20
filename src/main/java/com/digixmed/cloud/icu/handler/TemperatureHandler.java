package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 体温处理器
 *
 * 业务目的：处理体温体征，支持复测逻辑
 * 源数据：param_T.strVal → vitalsignNVal1, param_tiWenBuWei.strVal → vitalsignSVal1
 * 输出：vitalsignName=体温, vitalsignType=1001, unit=℃
 *
 * 复测逻辑：
 *   1. 若标准时间点体温数值 < 38.5 → vitalsignNVal2 = 空
 *   2. 若标准时间点体温 >= 38.5
 *      a. vitalsignSVal1 = 原始体温值
 *      b. 查询 (标准时间点, 标准时间点+1小时] 内同一患者有效的param_T
 *      c. 如果复测值 >= 38.5 → vitalsignNVal2 = 复测值
 *      d. 如果复测值 < 38.5 或不存在 → vitalsignNVal2 = 空
 *
 * 如果标准点记录存在多条：
 *   - 优先valid=true
 *   - 再取editTime最新的一条
 *   - 日志记录候选数量和选中bedsideId
 */
@Component
public class TemperatureHandler extends BaseVitalSignHandler {

    private static final double RECHECK_THRESHOLD = 38.5;
    private static final String SOURCE_CODE = "param_T";
    private static final String LOCATION_CODE = "param_tiWenBuWei";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    public TemperatureHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        if (bedside == null) {
            log.warn("STEP_04_SOURCE_RECORD_SELECTED traceId={} 温度记录为空", traceId);
            return null;
        }

        String bedsideId = getValueFromDocByKey(bedside, "_id", Object.class) != null
                ? getValueFromDocByKey(bedside, "_id", Object.class).toString() : "unknown";
        String pid = getValueFromDocByKey(bedside, "pid", String.class);
        String strVal = getValueFromDocByKey(bedside, "strVal", String.class);
        Boolean valid = getValueFromDocByKey(bedside, "valid", Boolean.class);

        // 解析体温值
        Double tempValue = parseDouble(strVal);
        if (tempValue == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} bedsideId={} 无法解析体温值: {}", traceId, bedsideId, strVal);
            return null;
        }

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} bedsideId={} 体温值={} valid={}",
                traceId, bedsideId, tempValue, valid);

        // 查询体温部位（独立的bedside记录）
        String location = findTemperatureLocation(pid, planTime, traceId);

        // 构建payload
        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("体温")
                .vitalsignType("1001")
                .vitalsignNVal1(formatDouble(tempValue))
                .vitalsignSVal1(location != null ? location : "")
                .unit("℃")
                .build();

        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId);

        // 默认传空字符串，保证报文中始终存在<vitalsignNVal2>节点
        payload.setVitalsignNVal2("");

        // 复测逻辑：体温 >= 38.5℃ 时设置复测标记，由 TemperatureRecheckTask 异步处理
        if (tempValue >= RECHECK_THRESHOLD) {
            payload.setRecheckRequired(true);
            payload.setRecheckCompleted(false);
            log.info("STEP_06_RECHECK traceId={} 原始值={} >= 38.5，标记需要复测", traceId, formatDouble(tempValue));
        }

        return payload;
    }

    /**
     * 查询体温部位
     * param_tiWenBuWei是独立的bedside记录，不是param_T文档中的嵌套字段
     */
    private String findTemperatureLocation(String pid, LocalDateTime planTime, String traceId) {
        ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(planTime.toLocalDate(), planTime.getHour());
        if (window == null) {
            log.warn("TEMP_LOCATION traceId={} pid={} 无法构建时间窗口 planTime={}", traceId, pid, planTime);
            return null;
        }
        Date startTime = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date endTime = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(LOCATION_CODE)
                .and("valid").ne(false)
                .and("time").gte(startTime).lt(endTime)
        ).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "editTime"))
                .limit(1);

        Document record = mongoTemplate.findOne(query, Document.class, "bedside");
        if (record != null) {
            String location = getValueFromDocByKey(record, "strVal", String.class);
            log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} 体温部位={}", traceId, location);
            return location;
        }
        return null;
    }

}

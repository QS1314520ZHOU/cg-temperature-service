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
 *      a. 查询 [标准时间点, 标准时间点+1小时) 内同一患者有效的param_T
 *      b. 排除作为首条值使用的原始记录
 *      c. 按bedside.time升序排列
 *      d. 取原记录之后最早的一条有效复测值
 *      e. 复测不存在则 vitalsignNVal2 = 空
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

        // 构建payload
        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("体温")
                .vitalsignType("1001")
                .vitalsignNVal1(formatDouble(tempValue))
                .vitalsignSVal1(getValueFromDocByKey(bedside, "param_tiWenBuWei.strVal", String.class))
                .unit("℃")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);

        // 复测逻辑：体温 >= 38.5℃
        if (tempValue >= RECHECK_THRESHOLD) {
            String recheckValue = findRecheckValue(pid, planTime, bedsideId, traceId);
            payload.setVitalsignNVal2(recheckValue);
            log.info("STEP_06_RECHECK traceId={} recheckValue={}", traceId, recheckValue);
        }

        return payload;
    }

    /**
     * 查找复测体温值
     *
     * @param pid 患者MongoDB ID
     * @param planTime 标准时间点
     * @param originalBedsideId 原始记录bedsideId
     * @param traceId 追踪ID
     * @return 复测值字符串，未找到返回null
     */
    private String findRecheckValue(String pid, LocalDateTime planTime, String originalBedsideId, String traceId) {
        ClinicalTimeWindow recheckWindow = timeWindowService.buildTemperatureRecheckWindow(planTime);
        Date start = Date.from(recheckWindow.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date end = Date.from(recheckWindow.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        // 查询窗口内的有效param_T记录
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(SOURCE_CODE)
                .and("valid").is(true)
                .and("time").gte(start).lt(end)
        ).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "time"));

        List<Document> records = mongoTemplate.find(query, Document.class, "bedside");

        if (records == null || records.isEmpty()) {
            log.info("STEP_06_RECHECK traceId={} 未找到复测记录", traceId);
            return null;
        }

        // 排除原始记录，取第一条
        for (Document record : records) {
            String recordId = getValueFromDocByKey(record, "_id", Object.class) != null
                    ? getValueFromDocByKey(record, "_id", Object.class).toString() : "unknown";
            if (!recordId.equals(originalBedsideId)) {
                String recheckStr = getValueFromDocByKey(record, "strVal", String.class);
                Double recheckValue = parseDouble(recheckStr);
                if (recheckValue != null) {
                    log.info("STEP_06_RECHECK traceId={} 找到复测记录 bedsideId={} value={}", traceId, recordId, recheckValue);
                    return formatDouble(recheckValue);
                }
            }
        }

        log.info("STEP_06_RECHECK traceId={} 窗口内无有效复测记录", traceId);
        return null;
    }
}

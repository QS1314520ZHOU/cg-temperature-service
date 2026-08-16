package com.digixmed.cloud.icu.controller;

import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.task.DailySummaryTask;
import com.digixmed.cloud.icu.task.PushTask;
import com.digixmed.cloud.icu.task.VitalSignScanTask;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 手动回传测试接口
 *
 * 使用场景：vitalsign.auto-enabled=false 时，所有定时任务停摆，
 * 由本接口按 mrn + 日期 + 小时 精准触发单个时间点的回传，便于联调核对报文。
 *
 * 时间点语义：
 *   2/6/10/14/18/22 → 生命体征（体温/脉搏/心率/呼吸/疼痛评分），精确匹配该时刻
 *   7               → 出入量汇总 + 大便次数 + 血压 + 身高体重（按 [前一天07:00, 当天07:00) 统计）
 */
@Api(tags = "体征手动回传测试")
@RestController
@RequestMapping("/api/manual")
public class ManualPushController {

    private static final Logger log = LoggerFactory.getLogger(ManualPushController.class);

    private static final Set<Integer> VITAL_HOURS = new HashSet<>(Arrays.asList(2, 6, 10, 14, 18, 22));
    private static final int SUMMARY_HOUR = 7;

    @Value("${vitalsign.manual.enabled:true}")
    private boolean manualEnabled;

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private VitalSignScanTask vitalSignScanTask;
    @Autowired private DailySummaryTask dailySummaryTask;
    @Autowired private PushTask pushTask;
    @Autowired private ClinicalTimeWindowService timeWindowService;

    @ApiOperation(value = "按患者+日期+时间点精准回传",
            notes = "示例：mrn=123, date=2026-08-16, hour=10 → 回传当天10:00的生命体征；" +
                    "hour=7 → 回传当天07:00的出入量汇总、大便次数、血压、身高体重")
    @PostMapping("/push")
    public Map<String, Object> push(
            @ApiParam(value = "患者MRN（对应 Mongo patient.mrn）", required = true, example = "123")
            @RequestParam String mrn,
            @ApiParam(value = "日期 yyyy-MM-dd", required = true, example = "2026-08-16")
            @RequestParam String date,
            @ApiParam(value = "时间点小时：2/6/10/14/18/22=生命体征，7=出入量汇总", required = true, example = "10")
            @RequestParam Integer hour,
            @ApiParam(value = "是否登记后立即推送", defaultValue = "true")
            @RequestParam(defaultValue = "true") boolean pushNow) {

        Map<String, Object> result = new LinkedHashMap<>();
        String traceId = TraceIdGenerator.generate();
        result.put("traceId", traceId);

        if (!manualEnabled) {
            result.put("success", false);
            result.put("message", "手动接口已关闭（vitalsign.manual.enabled=false）");
            return result;
        }

        LocalDate reportDate;
        try {
            reportDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            result.put("success", false);
            result.put("message", "日期格式错误，应为 yyyy-MM-dd，实际：" + date);
            return result;
        }

        if (hour == null || (!VITAL_HOURS.contains(hour) && hour != SUMMARY_HOUR)) {
            result.put("success", false);
            result.put("message", "时间点非法，只支持 2/6/10/14/18/22（生命体征）或 7（出入量汇总），实际：" + hour);
            return result;
        }

        Document patient = mongoTemplate.findOne(
                Query.query(Criteria.where("mrn").is(mrn)), Document.class, "patient");
        if (patient == null) {
            result.put("success", false);
            result.put("message", "未找到 mrn=" + mrn + " 对应的患者");
            return result;
        }
        String pid = String.valueOf(patient.get("_id"));
        result.put("pid", pid);
        result.put("patientName", patient.getString("name"));

        try {
            int enqueued;
            if (hour == SUMMARY_HOUR) {
                log.info("MANUAL traceId={} mrn={} date={} 走出入量汇总链路", traceId, mrn, reportDate);
                enqueued = dailySummaryTask.summarizeOnePatient(pid, reportDate, traceId);
                result.put("branch", "SUMMARY(出入量/大便次数/血压/身高体重)");
            } else {
                LocalDateTime point = timeWindowService.buildVitalPoint(reportDate, hour);
                log.info("MANUAL traceId={} mrn={} point={} 走生命体征链路", traceId, mrn, point);
                enqueued = vitalSignScanTask.scanOnePoint(pid, point, traceId);
                result.put("branch", "VITAL_SIGN(体温/脉搏/心率/呼吸/疼痛评分)");
                result.put("point", point.toString());
            }

            result.put("enqueued", enqueued);
            if (enqueued == 0) {
                result.put("success", true);
                result.put("message", "该时间点无数据或数据未变化，未登记新记录");
                return result;
            }

            if (pushNow) {
                pushTask.pushOnce(traceId);
                result.put("pushed", true);
            }
            result.put("success", true);
            result.put("message", "登记 " + enqueued + " 条" + (pushNow ? "并已触发推送" : "，待推送"));
        } catch (Exception e) {
            log.error("MANUAL traceId={} 手动回传异常", traceId, e);
            result.put("success", false);
            result.put("message", "执行异常：" + e.getMessage());
        }
        return result;
    }

    @ApiOperation(value = "查询回传队列", notes = "按 mrn 查看该患者已登记的回传记录及推送状态")
    @GetMapping("/queue")
    public List<Map<String, Object>> queue(
            @ApiParam(value = "患者MRN", required = true, example = "123") @RequestParam String mrn,
            @ApiParam(value = "返回条数", defaultValue = "50") @RequestParam(defaultValue = "50") int limit) {

        Query query = Query.query(Criteria.where("patientId").is(mrn))
                .with(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
                .limit(Math.min(limit, 200));

        List<Document> docs = mongoTemplate.find(query, Document.class,
                IntermediateService.PUSH_COLLECTION);

        List<Map<String, Object>> list = new ArrayList<>();
        for (Document d : docs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vitalsignName", d.getString("vitalsignName"));
            m.put("vitalsignType", d.getString("vitalsignType"));
            m.put("planTime", d.get("planTime"));
            m.put("NVal1", d.getString("vitalsignNVal1"));
            m.put("SVal1", d.getString("vitalsignSVal1"));
            m.put("unit", d.getString("unit"));
            m.put("status", d.getString("status"));
            m.put("retryCount", d.get("retryCount"));
            m.put("lastError", d.getString("lastErrorMessage"));
            list.add(m);
        }
        return list;
    }

    @ApiOperation(value = "触发入科第一条体征回传",
            notes = "根据患者 icuAdmissionTime 自动计算入科标准时刻，触发生命体征+身高体重回传")
    @PostMapping("/admission")
    public Map<String, Object> admission(
            @ApiParam(value = "患者MRN（对应 Mongo patient.mrn）", required = true, example = "123")
            @RequestParam String mrn,
            @ApiParam(value = "是否登记后立即推送", defaultValue = "true")
            @RequestParam(defaultValue = "true") boolean pushNow) {

        Map<String, Object> result = new LinkedHashMap<>();
        String traceId = TraceIdGenerator.generate();
        result.put("traceId", traceId);

        if (!manualEnabled) {
            result.put("success", false);
            result.put("message", "手动接口已关闭（vitalsign.manual.enabled=false）");
            return result;
        }

        Document patient = mongoTemplate.findOne(
                Query.query(Criteria.where("mrn").is(mrn)), Document.class, "patient");
        if (patient == null) {
            result.put("success", false);
            result.put("message", "未找到 mrn=" + mrn + " 对应的患者");
            return result;
        }
        String pid = String.valueOf(patient.get("_id"));
        result.put("pid", pid);
        result.put("patientName", patient.getString("name"));

        Date icuAdmissionTime = patient.get("icuAdmissionTime") instanceof Date
                ? (Date) patient.get("icuAdmissionTime") : null;
        if (icuAdmissionTime == null) {
            result.put("success", false);
            result.put("message", "该患者无 icuAdmissionTime 字段，无法触发入科体征");
            return result;
        }
        LocalDateTime admissionTime = icuAdmissionTime.toInstant()
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        result.put("icuAdmissionTime", admissionTime.toString());

        try {
            log.info("MANUAL traceId={} mrn={} admissionTime={} 走入科体征链路", traceId, mrn, admissionTime);
            int enqueued = vitalSignScanTask.scanAdmission(pid, traceId);
            result.put("enqueued", enqueued);
            result.put("branch", "ADMISSION(入科生命体征+身高体重)");

            if (enqueued <= 0) {
                result.put("success", true);
                result.put("message", "入科时刻无数据或无体温记录，未登记新记录");
                return result;
            }

            if (pushNow) {
                pushTask.pushOnce(traceId);
                result.put("pushed", true);
            }
            result.put("success", true);
            result.put("message", "登记 " + enqueued + " 条" + (pushNow ? "并已触发推送" : "，待推送"));
        } catch (Exception e) {
            log.error("MANUAL traceId={} 入科体征回传异常", traceId, e);
            result.put("success", false);
            result.put("message", "执行异常：" + e.getMessage());
        }
        return result;
    }

    @ApiOperation(value = "重置队列记录为待推送", notes = "把指定 mrn 的 SUCCESS 记录改回 FAILED，用于重复联调")
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam String mrn) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!manualEnabled) {
            result.put("success", false);
            result.put("message", "手动接口已关闭");
            return result;
        }
        long n = mongoTemplate.updateMulti(
                Query.query(Criteria.where("patientId").is(mrn)),
                new org.springframework.data.mongodb.core.query.Update()
                        .set("status", "FAILED")
                        .set("retryCount", 0)
                        .set("updatedAt", new Date()),
                IntermediateService.PUSH_COLLECTION).getModifiedCount();
        result.put("success", true);
        result.put("reset", n);
        return result;
    }

    @ApiOperation(value = "手动触发全量推送",
            notes = "立即执行一次 pushOnce，领取所有 PENDING/RETRY 记录并推送，不受 autoEnabled 开关影响")
    @PostMapping("/pushAll")
    public Map<String, Object> pushAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!manualEnabled) {
            result.put("success", false);
            result.put("message", "手动接口已关闭");
            return result;
        }
        String traceId = TraceIdGenerator.generate();
        log.info("MANUAL_PUSH_ALL traceId={} 手动触发全量推送", traceId);
        try {
            pushTask.pushOnce(traceId);
            result.put("success", true);
            result.put("traceId", traceId);
            result.put("message", "已触发一轮推送，请查看日志确认结果");
        } catch (Exception e) {
            log.error("MANUAL_PUSH_ALL traceId={} 推送异常", traceId, e);
            result.put("success", false);
            result.put("message", "推送异常：" + e.getMessage());
        }
        return result;
    }
}

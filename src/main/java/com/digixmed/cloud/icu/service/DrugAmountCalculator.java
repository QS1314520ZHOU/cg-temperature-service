package com.digixmed.cloud.icu.service;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 药物执行入量计算器
 *
 * 从 icu-stats-form-jar 的 hljld-form.utils.ts 移植：
 * - calcContinuousDrugAmount：按 drugActionList 积分持续用药实际入量
 * - sumDrugAmountsByChannel：按通道（静脉/输血/胃肠/肠内营养）聚合
 *
 * 数据源：drugExe 集合（药物执行记录）、configDrugMethod 集合（给药方法配置）
 */
@Component
public class DrugAmountCalculator {

    private static final Logger log = LoggerFactory.getLogger(DrugAmountCalculator.class);

    private static final BigDecimal MS_PER_HOUR = new BigDecimal("3600000");

    /** 参与出入量统计的入量通道 */
    private static final Set<String> COUNTED_IN_CHANNELS = Set.of("胃肠", "静脉", "输血");

    /** 变更速度的动作 */
    private static final Set<String> SPEED_ACTIONS = Set.of("start", "recovery", "add", "minus");

    /** 肠内营养泵入判定 */
    private static final String ENTERAL_NUTRITION_PATTERN = "肠内营养";

    @Autowired
    private MongoTemplate mongoTemplate;

    /* ==== 数据结构 ==== */

    public static class DrugActualAmount {
        /** 落在统计区间内的实际入量，全精度不舍入 */
        public final BigDecimal inRange;
        /** 全程累计实际入量（已封顶），用于自检 */
        public final BigDecimal total;
        /** 无动作数据，已回退为开始时点全额计入 */
        public final boolean fallback;

        public DrugActualAmount(BigDecimal inRange, BigDecimal total, boolean fallback) {
            this.inRange = inRange;
            this.total = total;
            this.fallback = fallback;
        }
    }

    public static class DrugChannelTotals {
        /** 静脉途径 → 入量（如 ivgtt, iv泵, im 等） */
        public final Map<String, BigDecimal> vein = new LinkedHashMap<>();
        /** 胃肠途径 → 入量（如 po, 鼻饲 等） */
        public final Map<String, BigDecimal> gastro = new LinkedHashMap<>();
        /** 输血入量 */
        public BigDecimal transfusion = BigDecimal.ZERO;
        /** 肠内营养泵入（归入鼻饲量） */
        public BigDecimal enteral = BigDecimal.ZERO;
    }

    private static class DrugSegment {
        final long start;
        final long end;
        final BigDecimal speed;

        DrugSegment(long start, long end, BigDecimal speed) {
            this.start = start;
            this.end = end;
            this.speed = speed;
        }
    }

    private static class DrugBolus {
        final long time;
        final BigDecimal amount;

        DrugBolus(long time, BigDecimal amount) {
            this.time = time;
            this.amount = amount;
        }
    }

    /* ==== 查询方法 ==== */

    /**
     * 查询区间内的药物执行记录（区间相交）
     * 跨 07:00 仍在执行的持续用药必须取回
     */
    public List<Document> queryDrugExe(String pid, Date rangeStart, Date rangeEnd) {
        Criteria overlap = new Criteria().andOperator(
                Criteria.where("startTime").lte(rangeEnd),
                new Criteria().orOperator(
                        Criteria.where("endTime").exists(false),
                        Criteria.where("endTime").is(null),
                        Criteria.where("endTime").gt(rangeStart)));

        Query query = new Query(Criteria.where("pid").is(pid)
                .and("status").ne("invalid")
                .andOperator(overlap));
        query.with(Sort.by(Sort.Direction.ASC, "startTime"));
        return mongoTemplate.find(query, Document.class, "drugExe");
    }

    /**
     * 查询所有有效的给药方法配置
     */
    public List<Document> queryDrugMethods() {
        Query query = new Query(Criteria.where("valid").ne(false));
        return mongoTemplate.find(query, Document.class, "configDrugMethod");
    }

    /* ==== 核心算法 ==== */

    /**
     * 按 drugActionList 计算持续用药在 (rangeStart, rangeEnd] 内的实际入量。
     *
     * 规则（逐条移植自 hljld-form.utils.ts）：
     * 1. speed 为变更后的绝对速度，单位 ml/h
     * 2. pause 期间速度记 0，recovery 恢复
     * 3. quickAdd 为瞬时快推，不改变速度，占用 liquidAmount 额度
     * 4. 累计量封顶到 liquidAmount，触顶即视为在跑满那一刻结束
     * 5. 全程保留毫秒精度，不做分钟归一
     */
    public DrugActualAmount calcContinuousDrugAmount(
            Document execution, long rangeStartMs, long rangeEndMs, boolean startExclusive) {

        Date startTime = execution.getDate("startTime");
        if (startTime == null) {
            return new DrugActualAmount(BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        long startMs = startTime.getTime();

        BigDecimal cap = resolveLiquidCap(execution);
        boolean hasCap = cap.compareTo(BigDecimal.ZERO) > 0;

        Date endTime = execution.getDate("endTime");
        long endRaw = endTime != null ? endTime.getTime() : Long.MAX_VALUE;
        long cutoff = Math.min(endRaw == Long.MAX_VALUE ? System.currentTimeMillis() : endRaw, rangeEndMs);
        if (cutoff <= startMs) {
            return new DrugActualAmount(BigDecimal.ZERO, BigDecimal.ZERO, false);
        }

        // 解析 drugActionList
        List<Map<String, Object>> actionList = getDrugActionList(execution);
        List<Map.Entry<Map<String, Object>, Long>> actions = new ArrayList<>();
        for (Map<String, Object> item : actionList) {
            Object timeObj = item.get("time");
            if (timeObj instanceof Date) {
                actions.add(Map.entry(item, ((Date) timeObj).getTime()));
            }
        }
        actions.sort(Comparator.comparingLong(Map.Entry::getValue));

        // 无动作数据：回退为开始时点全额计入
        if (actions.isEmpty()) {
            boolean hit = inRange(startMs, rangeStartMs, rangeEndMs, startExclusive);
            return new DrugActualAmount(hit ? cap : BigDecimal.ZERO, cap, true);
        }

        List<DrugSegment> segments = new ArrayList<>();
        List<DrugBolus> boluses = new ArrayList<>();
        long cursor = startMs;
        BigDecimal speed = BigDecimal.ZERO;
        boolean stopped = false;

        for (Map.Entry<Map<String, Object>, Long> entry : actions) {
            Map<String, Object> raw = entry.getKey();
            long ts = entry.getValue();
            long at = Math.min(Math.max(ts, cursor), cutoff);

            if (at > cursor && speed.compareTo(BigDecimal.ZERO) > 0) {
                segments.add(new DrugSegment(cursor, at, speed));
            }
            cursor = at;

            String action = getString(raw, "action");
            if ("quickAdd".equals(action)) {
                BigDecimal amount = parseBigDecimal(raw.get("quickAddAmount"));
                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    boluses.add(new DrugBolus(at, amount));
                }
                continue; // 快推不改变速度
            }
            if ("pause".equals(action)) {
                speed = BigDecimal.ZERO;
                continue;
            }
            if ("stop".equals(action)) {
                speed = BigDecimal.ZERO;
                stopped = true;
                break;
            }
            if (SPEED_ACTIONS.contains(action)) {
                speed = normalizeSpeed(raw);
                continue;
            }
            // 未知动作：带 speed 视为调速
            if (raw.containsKey("speed")) {
                speed = normalizeSpeed(raw);
            }
        }

        if (!stopped && cursor < cutoff && speed.compareTo(BigDecimal.ZERO) > 0) {
            segments.add(new DrugSegment(cursor, cutoff, speed));
        }

        // 按时间顺序积分，边算边封顶
        BigDecimal used = BigDecimal.ZERO;
        BigDecimal inRange = BigDecimal.ZERO;

        // 合并事件：bolus(order=0) + segment(order=1)
        List<Object[]> events = new ArrayList<>();
        for (DrugBolus b : boluses) {
            events.add(new Object[]{b.time, 0, null, b});
        }
        for (DrugSegment s : segments) {
            events.add(new Object[]{s.start, 1, s, null});
        }
        events.sort((a, b) -> {
            int cmp = Long.compare((long) a[0], (long) b[0]);
            return cmp != 0 ? cmp : Integer.compare((int) a[1], (int) b[1]);
        });

        for (Object[] ev : events) {
            BigDecimal avail = hasCap ? cap.subtract(used).max(BigDecimal.ZERO) : null;
            if (avail != null && avail.compareTo(BigDecimal.ZERO) <= 0) break;

            if (ev[2] != null) {
                // Segment
                DrugSegment seg = (DrugSegment) ev[2];
                BigDecimal amount = seg.speed.multiply(new BigDecimal(seg.end - seg.start))
                        .divide(MS_PER_HOUR, 10, RoundingMode.HALF_UP);
                long effEnd = seg.end;
                if (avail != null && amount.compareTo(avail) > 0) {
                    amount = avail;
                    effEnd = seg.start + avail.divide(seg.speed, 10, RoundingMode.HALF_UP)
                            .multiply(MS_PER_HOUR).longValue();
                }
                used = used.add(amount);
                long ovStart = Math.max(seg.start, rangeStartMs);
                long ovEnd = Math.min(effEnd, rangeEndMs);
                if (ovEnd > ovStart) {
                    inRange = inRange.add(seg.speed.multiply(new BigDecimal(ovEnd - ovStart))
                            .divide(MS_PER_HOUR, 10, RoundingMode.HALF_UP));
                }
            } else if (ev[3] != null) {
                // Bolus
                DrugBolus bolus = (DrugBolus) ev[3];
                BigDecimal amount = bolus.amount;
                if (avail != null) amount = amount.min(avail);
                if (amount.compareTo(BigDecimal.ZERO) <= 0) continue;
                used = used.add(amount);
                long t = bolus.time;
                boolean hit = startExclusive
                        ? (t > rangeStartMs && t <= rangeEndMs)
                        : (t >= rangeStartMs && t <= rangeEndMs);
                if (hit) {
                    inRange = inRange.add(amount);
                }
            }
        }

        if (hasCap) {
            inRange = inRange.min(cap);
        }
        return new DrugActualAmount(inRange, used, false);
    }

    /**
     * 区间内药物执行的实际入量，按通道与途径聚合。
     * isOnce === false 走速度积分；其余按给药时点全额计入。
     */
    public DrugChannelTotals sumDrugAmountsByChannel(
            List<Document> drugExecutions, List<Document> drugMethods,
            long rangeStartMs, long rangeEndMs, boolean startExclusive) {

        DrugChannelTotals totals = new DrugChannelTotals();

        for (Document execution : drugExecutions) {
            // 过滤无效记录
            if (!isValidDrugExecution(execution)) continue;

            String methodCode = getString(execution, "methodCode");
            Document method = findDrugMethod(methodCode, drugMethods);
            if (method == null) continue;

            // inChannel 必须严格等于三种入量通道之一
            String inCh = getString(method, "inChannel");
            if (inCh == null || !COUNTED_IN_CHANNELS.contains(inCh)) continue;

            BigDecimal amount;
            Boolean isOnce = method.getBoolean("isOnce");
            if (Boolean.FALSE.equals(isOnce)) {
                DrugActualAmount result = calcContinuousDrugAmount(
                        execution, rangeStartMs, rangeEndMs, startExclusive);
                amount = result.inRange;
            } else {
                Date startTime = execution.getDate("startTime");
                if (startTime == null || !inRange(startTime.getTime(), rangeStartMs, rangeEndMs, startExclusive)) {
                    continue;
                }
                amount = resolveLiquidCap(execution);
            }

            if (amount.compareTo(BigDecimal.ZERO) == 0) continue;

            String channel = methodChannel(method);
            switch (channel) {
                case "transfusion":
                    totals.transfusion = totals.transfusion.add(amount);
                    break;
                case "enteral":
                    totals.enteral = totals.enteral.add(amount);
                    break;
                case "gastro": {
                    String route = routeLabel(getString(method, "name"));
                    totals.gastro.merge(route, amount, BigDecimal::add);
                    break;
                }
                case "vein": {
                    String route = routeLabel(getString(method, "name"));
                    totals.vein.merge(route, amount, BigDecimal::add);
                    break;
                }
                default:
                    break;
            }
        }

        return totals;
    }

    /* ==== 辅助方法 ==== */

    /**
     * 匹配给药方法配置。code 字段可能是 `、` 分隔的多值。
     */
    public Document findDrugMethod(String methodCode, List<Document> configs) {
        if (methodCode == null || methodCode.trim().isEmpty()) return null;
        String targetCode = methodCode.trim();
        for (Document config : configs) {
            if (Boolean.FALSE.equals(config.getBoolean("valid"))) continue;
            String code = config.getString("code");
            if (code == null) continue;
            List<String> codes = normalizeCodes(code);
            if (codes.contains(targetCode)) return config;
        }
        return null;
    }

    private List<String> normalizeCodes(String code) {
        if (code == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String item : code.split("、")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /**
     * 通道归类：配置优先（inChannel / group），名称正则兜底
     */
    private String methodChannel(Document method) {
        String group = getString(method, "group");
        String channel = getString(method, "inChannel");
        String name = getString(method, "name");

        if ("输血".equals(group) || "输血".equals(channel)) return "transfusion";
        if (name != null && name.contains(ENTERAL_NUTRITION_PATTERN)) return "enteral";
        if ("胃肠".equals(group) || "胃肠".equals(channel) || "消化道".equals(channel)) return "gastro";
        if ("静脉".equals(channel)) return "vein";
        return "other";
    }

    /**
     * 速度归一为 ml/h；单位缺失按 ml/h 处理
     */
    private BigDecimal normalizeSpeed(Map<String, Object> action) {
        BigDecimal speed = parseBigDecimal(action.get("speed"));
        if (speed.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        String unit = getString(action, "speedUnit");
        if (unit != null && unit.trim().toLowerCase().equals("ml/min")) {
            speed = speed.multiply(new BigDecimal("60"));
        }
        return speed;
    }

    /**
     * 顶层 liquidAmount 优先，缺失时回退 drugList 求和
     */
    private BigDecimal resolveLiquidCap(Document execution) {
        BigDecimal top = parseBigDecimal(execution.get("liquidAmount"));
        if (top.compareTo(BigDecimal.ZERO) > 0) return top;

        List<Document> drugList = getDrugList(execution);
        BigDecimal sum = BigDecimal.ZERO;
        for (Document d : drugList) {
            sum = sum.add(parseBigDecimal(d.get("liquidAmount")));
        }
        return sum;
    }

    private boolean isValidDrugExecution(Document execution) {
        if (execution == null) return false;
        if ("invalid".equals(getString(execution, "status"))) return false;
        return execution.getDate("startTime") != null;
    }

    private boolean inRange(long ts, long rangeStartMs, long rangeEndMs, boolean startExclusive) {
        return (startExclusive ? ts > rangeStartMs : ts >= rangeStartMs) && ts <= rangeEndMs;
    }

    /**
     * 途径标签（简化版，用于 Map key）
     */
    private String routeLabel(String name) {
        if (name == null) return "";
        if (name.contains("输液泵") || name.contains("静滴")) return "ivgtt";
        if (name.contains("微量泵")) return "iv泵";
        if (name.contains("肌肉注射")) return "im";
        if (name.contains("皮下注射")) return "IH";
        if (name.contains("静注")) return "iv";
        if (name.contains("口服")) return "po";
        if (name.contains("胃管置管术")) return "鼻饲";
        if (name.contains("肠内营养")) return "鼻饲注入";
        return name;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getDrugActionList(Document execution) {
        Object obj = execution.get("drugActionList");
        if (obj instanceof List) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : (List<?>) obj) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                } else if (item instanceof Document) {
                    result.add((Document) item);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<Document> getDrugList(Document execution) {
        Object obj = execution.get("drugList");
        if (obj instanceof List) {
            List<Document> result = new ArrayList<>();
            for (Object item : (List<?>) obj) {
                if (item instanceof Document) {
                    result.add((Document) item);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString().trim() : null;
    }

    private String getString(Document doc, String key) {
        Object val = doc.get(key);
        return val != null ? val.toString().trim() : null;
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof Number) return new BigDecimal(value.toString());
        if (value instanceof String) {
            String s = ((String) value).replace(",", ".").trim();
            if (s.isEmpty()) return BigDecimal.ZERO;
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }
}

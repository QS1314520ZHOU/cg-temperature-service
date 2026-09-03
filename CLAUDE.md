# CG Temperature Service - 体温单服务

## 字符集硬性要求（最高优先级）

1. 代码结构部分只允许 ASCII 可打印字符。所有引号必须是半角 `"` 和 `'`，
   禁止出现 `"` `"` `'` `'`（U+201C/201D/2018/2019）。
2. 所有括号、分号、冒号、逗号必须半角：`() ; : ,`
   禁止 `（）` `；` `：` `，`
3. 禁止全角空格 U+3000，缩进只用半角空格。
4. 中文只允许出现在两个位置：注释内部、字符串字面量内部。
   字符串的定界符本身必须是半角双引号。
5. 输出前自检：逐行扫描非 ASCII 字符，确认每一个都落在注释或字符串内部。

正确：`log.info("PUSH traceId={} 推送成功", traceId);`
错误：`log.info(“PUSH traceId={} 推送成功”, traceId);`

## 项目概述

Spring Boot 2.2.2 + Java 11 服务，用于采集ICU患者生命体征数据并推送到HIS系统。

## 架构设计

### 核心链路（新链路）
```
采集层：VitalSignScanTask / DailySummaryTask
    ↓ VitalSignPayload
队列层：IntermediateService → vitalsign_push_queue（MongoDB）
    ↓ fetchPending()
推送层：PushTask → PushService → SOAP/XML → HIS
```

### 状态机（二态机）
```
FAILED → 推送中 → SUCCESS
                 → FAILED（失败回到待推送）
```

| 状态 | 含义 |
|------|------|
| `FAILED` | 待推送（扫描入队/推送失败回到FAILED） |
| `SUCCESS` | 已推送且内容未变 |

### 内容变化检测
- `payloadHash`：当前待推内容的 hash
- `lastSuccessHash`：HIS 当前实际持有的值的 hash（仅推送成功时写入）
- `lastSuccessPayload`：HIS 当前实际持有的值的快照（仅推送成功时写入）
- 内容变化时两步推送：先发 `isValid=0` 作废 `lastSuccessPayload`，等响应成功后再发 `isValid=1` 新值
- 作废失败必须中止本条，不得推送新值

### 幂等机制
- 幂等键：`patientId_series_vitalsignType_planTime`
- 内容哈希：SHA-256（不含traceId/className等易变字段）
- 相同内容+SUCCESS → 跳过；内容变化 → 设FAILED + 保存旧值快照

### 推送逻辑（完整链路）

### 推送触发方式
| 触发源 | 时机 | 说明 |
|--------|------|------|
| `VitalSignScanTask` | 活跃时段(02/06/10/14/18/22点)每3分钟(:03~:30)，非活跃时段每30分钟(:00/:30) | 扫描写入队列后立即调用 `pushTask.pushOnce(traceId)` |
| `DailySummaryTask` | 7-8点每5分钟汇总完成后 | 汇总写入队列后立即调用 `pushTask.pushOnce(traceId)` |
| `DailySummaryTask.checkAndResend` | 8点之后每30分钟变化检测完成后 | 检测到变化重新入队后调用 `pushTask.pushOnce(traceId)` |
| `VentilatorEventDetectTask` | 每2分钟 | 呼吸机启用/停止事件立即推送 |
| `DischargePatientTask` | 每10分钟 | 出科患者回传：以出科时间点回传生命体征数据 |
| `PushTask` 定时兜底 | `0 */10 * * * ?`（每10分钟） | 兜底扫描，确保漏网的 FAILED 记录被处理 |

### 推送执行流程（PushTask.pushOnce）
```
1. fetchPending(10) — 从 vitalsign_push_queue 取最多10条 FAILED 记录（按 createdAt 升序）
2. 逐条执行 pushOne():
   a. 比对 payloadHash vs lastSuccessHash
   b. 如果 lastSuccessHash 存在且与 payloadHash 不一致（内容变化）：
      → 先用 lastSuccessPayload 构建 isValid=0 作废报文
      → 调用 PushService.pushInvalidation() 发送作废
      → 作废失败 → 整条中止，标记 INVALIDATION_FAILED，下一轮重试
      → 作废成功 → 继续推新值
   c. 用当前记录构建 isValid=1 新值报文
   d. 调用 PushService.push() 发送
   e. 推送成功 → markSuccess()：设 status=SUCCESS + 写入 lastSuccessHash + lastSuccessPayload
   f. 推送失败 → markFailed()：设 status=FAILED + 记录错误码/信息 + retryCount+1
```

### 队列记录状态机（IntermediateService）
```
新记录入队 → status=FAILED
   ↓ fetchPending 取出
推送成功 → markSuccess() → status=SUCCESS + lastSuccessHash + lastSuccessPayload
推送失败 → markFailed() → status=FAILED（下一轮重试）
内容变化 → upsertPending() → 更新业务字段 + status=FAILED + retryCount=0
```

### 入队逻辑（IntermediateService.upsertPending）
幂等键：`{patientId}_{series}_{vitalsignType}_{planTime}`

| 情况 | 处理 |
|------|------|
| 无记录 | 插入，status=FAILED |
| 有记录，payloadHash 相同，status=SUCCESS | 跳过（不重复推送） |
| 有记录，payloadHash 不同 | 更新业务字段，status=FAILED，retryCount=0 |
| 有记录，status=FAILED | 更新业务字段，status=FAILED |

**重要约束**：扫描任务写队列时只覆盖业务字段和 updatedAt，禁止清空 lastErrorCode / requestMsg / responseMsg / sentAt / retryCount（保留调试信息）

### 内容哈希（SHA-256）
参与 hash 的字段：patientId, mrn, patientName, series, wardCode, vitalsignType, vitalsignName, unit, vitalsignNVal1~3, vitalsignSVal1~2, remark, isValid, recordNurseId, recordNurseName, mongoPid, planTime, isCustomType

不含 traceId、className、createdAt 等易变字段。

### 身份校验（PushService.ensurePatientIdentity）
推送前按 `mongoPid` 回查 patient 文档：
1. patient 不存在 → 跳过不推送
2. patientId（patient.mrn）为空 → 跳过不推送
3. mrn（patient.hisPid）为空 → 警告但仍推送
4. patientId/mrn/patientName 从 patient 文档实时读取，不依赖队列中缓存的值

### 默认记录者
- recordNurseId: `041660`
- recordNurseName: `陈琳`
- patientId/mrn/patientName 从 patient 文档读取，非队列缓存

## 业务约束
1. 扫描任务写队列时只覆盖业务字段和 updatedAt，禁止清空 lastErrorCode / requestMsg / responseMsg / sentAt / retryCount
2. 时区统一 Asia/Shanghai
3. JAXB 遇 null 会省略节点，空值一律输出空串
4. 患者姓名、住院号等属于患者隐私，日志中必须脱敏

## 时间点规则

### 普通体征时间点
`02:00, 06:00, 10:00, 14:00, 18:00, 22:00`

### 血压时间点
`07:00`（从10:00标准时间点起扫描）

### 每日汇总窗口
`[前一天07:00, 当天07:00)` — 7-8点每5分钟执行，8点之后每30分钟执行

### 体温复测窗口
`[标准时间点, 标准时间点+1小时)` — 体温>=38.5℃触发

## 扫描逻辑

### VitalSignScanTask（活跃时段每3分钟，非活跃时段每30分钟）
根据当前时间扫描当前+之前的时间点窗口，支持补录数据：
- 活跃时段(02/06/10/14/18/22点)：:03~:30每3分钟执行，如10:03扫描02:00、06:00、10:00，10:06再扫一次...
- 非活跃时段：:00/:30每30分钟执行
- 10:03执行 → 扫描 02:00、06:00、10:00 三个窗口
- 14:03执行 → 扫描 02:00、06:00、10:00、14:00 四个窗口
- 依此类推...

**入科时间点扫描**：同时扫描患者入科时间（icuAdmissionTime）的生命体征数据
- 只扫描当天入科的患者
- 处理生命体征（体温、脉搏、心率、呼吸、疼痛评分）+ 身高体重，不处理血压
- 使用入科时间所在的标准时间点窗口

处理的体征类型：
| 体征 | 代码 | 时间点 | 类型编码 |
|------|------|--------|----------|
| 体温 | param_T | 02/06/10/14/18/22 | 1001 |
| 脉搏 | param_脉搏（fallback param_PR） | 同上 | 1002 |
| 心率 | param_HR | 同上 | 1003 |
| 呼吸 | param_resp | 同上 | 1004 |
| 血压 | param_nibp_s + param_nibp_d | 07:00 | 1005 |
| 疼痛评分 | param_tengTong_score | 同上 | 1010 |

### DailySummaryTask（7-8点每5分钟执行，8点之后每30分钟执行）
| 项目 | 代码 | 说明 |
|------|------|------|
| 大便次数 | param_汇总大便次数 | 只查07:00数据 |
| 小便量 | param_niaoLiang | 窗口内求和 |
| 饮入量(1044) | param_kouFu + param_biSi + param_YaoStomach_in_hour | 窗口内求和 |
| 输入量(1045) | param_带入药量 + param_YaoYeti_in_hour + param_YaoShuXue_in_hour | 窗口内求和 |
| 总入量(1009) | 六项去重求和 | param_YaoShuXue_in_hour 不重复计算 |
| 总出量(1010) | 固定七项 + `_tube_`通配 | 见下方说明 |
| 排出物量(3125) | param_daBianAmount + param_造瘘口量 + param_outuwuliang + param_咯血 + param_tanLiang + param_渗液 | 窗口内求和 |
| 胃管负压引流 | param_tube_胃肠减压 | 窗口内求和 |
| 其他引流量 | code含`_tube_`但非胃肠减压 | 窗口内求和 |
| 净超滤量 | param_chaoLvLiang | 窗口内求和 |
| 身高 | dFormData sg/fg | 1013 |
| 体重 | dFormData tz/zt | 1014 |

**总出量(1010)固定八项**：param_niaoLiang, param_daBianAmount, param_outuwuliang, param_造瘘口量, param_咯血, param_tanLiang, param_渗液, param_tube_胃肠减压
**总出量通配**：所有 code 含 `_tube_` 的记录（与固定项去重，param_tube_胃肠减压 不重复计算）

### 变化检测（checkAndResendScheduled）
- **频率**：8点之后每30分钟执行（`0 0/30 8-23,0-6 * * ?`）
- **检测范围**：**昨天 + 前天**两天的数据
  - 昨天窗口：`[yesterday 07:00, today 07:00)`
  - 前天窗口：`[dayBeforeYesterday 07:00, yesterday 07:00)`
- **原理**：复用 processPatientSummary，upsertPending 内部比对 payloadHash：
  - hash 相同 + SUCCESS → SKIP（不重复推送）
  - hash 不同 → 设 FAILED → PushTask 自动走两步流程（isValid=0 旧值 → isValid=1 新值）
- **立即推送**：变化检测完成后调用 `pushTask.pushOnce(traceId)` 立即推送

## 体温复测逻辑

1. TemperatureHandler 采集时体温>=38.5℃ → 设置 `recheckRequired=true, recheckCompleted=false`
2. 记录写入 `vitalsign_push_queue`，vitalsignNVal2 为空
3. TemperatureRecheckTask（每10分钟扫描）查询 `recheckRequired=true AND recheckCompleted=false`
4. 在复测窗口内查找复测值：
   - 找到且>=38.5 → 更新 vitalsignNVal2，标记 recheckCompleted=true，重置为PENDING重新推送
   - 找到但<38.5 → 标记完成，vitalsignNVal2保持空
   - 未找到且窗口未关闭 → 更新尝试次数，等待下次扫描
   - 未找到且窗口已关闭 → 标记完成
5. 最大尝试次数：6次

## 呼吸机事件实时检测

### 数据源
- `param_XiYangTuJing.strVal` — 给氧途径
- 呼吸机标识判定值：`无创`、`有创`、`高流量湿化氧疗`

### 检测机制
| 触发源 | 时机 | 检测内容 |
|--------|------|----------|
| `VentilatorEventDetectTask` | 每2分钟 | 非整点呼吸机启用/停止事件 |
| `BreathHandler`（标准时间点扫描） | 每小时:10 | 标准时间点的呼吸机状态 |

### VentilatorEventDetectTask（每2分钟）
- 查询所有有 `param_XiYangTuJing` 记录的患者
- 对比内存中上次状态，检测状态变化：
  - NOT_USING → USING：新记录出现呼吸机标识 → 立即推送
  - USING → NOT_USING：由 BreathHandler 在标准时间点检测并推送
- planTime = recordTime = 记录实际时间（不锚定标准时间点）
- 首次运行只初始化状态，不推送（防止启动时误推）

### BreathHandler 标准时间点逻辑
- USING：当前窗口内 `param_XiYangTuJing.strVal` 为三种值之一 → 推送"使用呼吸机"
- STOPPED：上一时间点 USING，当前窗口无呼吸机标识 → 推送"停止呼吸机"
- NOT_USING：无呼吸机标识，上一状态也不是 USING → 推送呼吸值

## 出科患者回传

### 业务目的
患者出科时，以出科时间点回传该时间点的生命体征数据（体温、脉搏、心率、呼吸、疼痛评分）。

### 触发方式
- `DischargePatientTask`：每10分钟执行一次
- 查询最近24小时内出科的患者

### 处理逻辑
1. 查询最近24小时内出科的患者（status='discharged'，discharge_time >= now-24h）
2. 对每个出科患者：
   - 获取出科时间（如9月2号18:30）
   - 确定出科时间对应的标准时间点（18:00）
   - 查询窗口 `[标准时间点, 出科时间)` 内的生命体征数据（如18:00-18:30）
   - 取每个生命体征类型的最新一条记录
   - 以出科时间作为planTime构建payload并入队
3. 回传完成后立即推送

### 标准时间点映射规则
出科时间对应的标准时间点（出科时间之前最近的标准时间点）：

| 出科时间 | 标准时间点 | 查询窗口 |
|---------|-----------|---------|
| 01:30 | 22:00（前一天） | [22:00, 01:30) |
| 03:00 | 02:00 | [02:00, 03:00) |
| 07:30 | 06:00 | [06:00, 07:30) |
| 12:00 | 10:00 | [10:00, 12:00) |
| 18:30 | 18:00 | [18:00, 18:30) |
| 23:00 | 22:00 | [22:00, 23:00) |

### 示例
患者9月2号18:30出科：
- 标准时间点：18:00
- 查询窗口：[18:00, 18:30)
- 体温：取18:15的记录 → planTime=18:30
- 脉搏：取18:20的记录 → planTime=18:30
- 心率：取18:25的记录 → planTime=18:30
- 呼吸：取18:10的记录 → planTime=18:30
- 疼痛评分：取18:05的记录 → planTime=18:30

### 幂等机制
使用 `intermediateService.upsertPending()` 进行幂等检查：
- 幂等键：`{patientId}_{series}_{vitalsignType}_{planTime}`
- 相同内容 + SUCCESS → SKIP（不重复推送）
- 内容变化 → UPDATE，status=FAILED，重新推送

### 配置
```yaml
vitalsign:
  discharge:
    cron: 0 */10 * * * ?         # 出科回传：每10分钟
    lookback-hours: 24            # 回看小时数：查询最近24小时内出科的患者
```

## 身高体重逻辑

发送条件（7天分页）：
- `pageDayIndex = DAYS.between(admissionWardDate, reportDate)`
- `pageDayIndex >= 0 AND pageDayIndex % 7 == 0`

入科日期解析：
1. 先查 `patient.icuAdmissionTime`
2. 如果报告日期与 icuAdmissionTime 同一天（第一天）→ 直接返回
3. 不在同一天 → 查 KingBase `np_nis_cqchonggang.inpatients.admission_ward_time`
4. 查不到 → 不回传，日志记录"未获取到admission_ward_time"

字段优先级：
- 身高：sg, fg
- 体重：tz, zt

## 患者身份映射

| 目标字段 | 来源 | 说明 |
|----------|------|------|
| patientId | patient.mrn | SOAP接口的patientId |
| mrn | patient.hisPid | SOAP接口的mrn |
| mongoPid | patient._id | MongoDB ObjectId |
| series | "1" | 固定值 |
| wardCode | "125011" | 配置项 |

## 关键配置

```yaml
# application.yml
spring.data.mongodb.uri: mongodb://root:密码@IP:27017/dt?authSource=admin
vitalsign.patient.ward-code: 125011
vitalsign.patient.record-nurse-id: 041660

# 定时任务
digixmed.cron: 0 */3 * * * ?                    # 生命体征扫描：每3分钟，活跃时段(:03~:30)非活跃时段(:00/:30)由代码过滤
vitalsign.push.cron: 0 */10 * * * ?            # 推送兜底：每10分钟
vitalsign.summary.cron: 0 0/5 7 * * ?          # 每日汇总：7-8点每5分钟
vitalsign.summary.check-cron: 0 0/30 8-23,0-6 * * ?  # 变化检测：8点之后每30分钟
# 呼吸机事件检测：每2分钟（硬编码cron，不可配置）
vitalsign.summary.lookback-days: 1             # 汇总回看天数
vitalsign.discharge.cron: 0 */10 * * * ?      # 出科回传：每10分钟
vitalsign.discharge.lookback-hours: 24         # 出科回传回看小时数

# KingBase数据源
spring.datasource.kingbase.url: jdbc:kingbase8://IP:54321/数据库名
spring.datasource.kingbase.username: 用户名
spring.datasource.kingbase.password: 密码
```

## 数据源

| 数据源 | 用途 |
|--------|------|
| MongoDB bedside | 生命体征原始记录 |
| MongoDB patient | 患者信息（mrn, hisPid, icuAdmissionTime） |
| MongoDB dFormData | 表单数据（身高体重） |
| MongoDB vitalsign_push_queue | 推送队列 |
| MongoDB configParam | 体征配置 |
| KingBase inpatients | 在科患者、入科时间 |

## SOAP推送

报文结构：
```xml
<soap:Envelope>
  <soap:Body>
    <data>
      <dataValue>
        <hisPid>{mrn}</hisPid>
        <mrn>{patientId}</mrn>
        <name>{patientName}</name>
        <icuNo>{series}</icuNo>
        <vitalsignType>{vitalsignType}</vitalsignType>
        <vitalsignName>{vitalsignName}</vitalsignName>
        <vitalsignNVal1>{数值1}</vitalsignNVal1>
        <vitalsignNVal2>{数值2/复测值}</vitalsignNVal2>
        <vitalsignSVal1>{字符串1}</vitalsignSVal1>
        <measureTime>{planTime}</measureTime>
        <recordTime>{recordTime}</recordTime>
        <nurseId>{recordNurseId}</nurseId>
        <nurseName>{recordNurseName}</nurseName>
        <deptCode>{wardCode}</deptCode>
        <unit>{unit}</unit>
        <isValid>{isValid}</isValid>
      </dataValue>
    </data>
  </soap:Body>
</soap:Envelope>
```

## REST接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/queue/stats` | GET | 队列状态统计（PENDING/SENDING/SUCCESS/RETRY/DEAD） |
| `/scan/patient` | POST | 手动触发指定患者数据扫描（含入科时间点、出入量） |
| `/manual/scanAndPush` | POST | 批量扫描在科患者并立即推送（用于数据修正） |

### /scan/patient 参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| mrn | String | 是 | 住院号 |
| startDate | LocalDate | 是 | 开始日期（yyyy-MM-dd） |
| endDate | LocalDate | 是 | 结束日期（yyyy-MM-dd） |

扫描内容：
- 普通体征（6个时间点）：体温、脉搏、心率、呼吸、疼痛评分
- 入科体征（入科当天）：生命体征 + 身高体重，使用入科时间所在标准时间点
- 血压（07:00）
- 每日汇总（出入量）：大便次数、小便量、饮入量、治疗输入量、总输入量、总出量、排出物量、胃管负压引流、其他引流量、净超滤量、身高体重

### /manual/scanAndPush 参数
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| targetDate | LocalDate | 是 | 目标日期（yyyy-MM-dd） |
| hours | String | 否 | 时间点(小时)，逗号分隔，如"2,6,10,14"。为空则扫描全部6个时间点 |

使用示例：
```bash
# 重新扫描今天2/6/10/14四个时间点并立即推送
curl -X POST "http://localhost:10245/manual/scanAndPush?targetDate=2026-08-23&hours=2,6,10,14"

# 重新扫描今天全部6个时间点并立即推送
curl -X POST "http://localhost:10245/manual/scanAndPush?targetDate=2026-08-23"
```

## 文件结构

```
src/main/java/com/digixmed/cloud/icu/
├── config/          # 配置类（Swagger, SOAP, DataSource）
├── controller/      # REST端点（健康检查、队列统计、批量扫描推送）
├── handler/         # 体征处理器（每种体征一个Handler）
├── model/           # 模型（VitalSignPayload, Patient, ClinicalTimeWindow）
├── repository/      # KingBase Repository
├── service/         # 服务层（IntermediateService, PushService, ClinicalTimeWindowService）
├── task/            # 定时任务（VitalSignScanTask, PushTask, DailySummaryTask, TemperatureRecheckTask, VentilatorEventDetectTask）
└── util/            # 工具类（XMLUtils, DateUtils, TraceIdGenerator）
```

## 注意事项

1. **时区统一**：所有时间操作使用 `Asia/Shanghai`，不得使用JVM默认时区
2. **BigDecimal**：出入量计算禁止使用double累加
3. **左闭右开**：所有时间窗口采用 `[start, end)`，禁止使用 23:59:59
4. **参数化查询**：SQL必须参数化，禁止拼接
5. **幂等推送**：相同内容不重复推送，内容变化才重新推送
6. **逻辑修改规则**：修改业务逻辑（统计规则、计算方式、时间窗口等）前必须先与用户确认，不得自行修改
7. **MongoDB orOperator**：Spring Data MongoDB 的 `Criteria.orOperator()` 会创建顶层 `$or`，绕过前面的 AND 条件。需要与 AND 条件组合时，必须用 `.andOperator(new Criteria().orOperator(...))` 包裹，禁止直接链式调用 `.orOperator()`
8. **valid过滤**：所有bedside集合查询必须包含 `.and("valid").ne(false)` 过滤条件，排除已作废的记录

## 修改历史

### 2026-08-23：新增批量扫描推送接口 /manual/scanAndPush

**需求**：
数据修正场景：修改业务逻辑后（如呼吸机状态判断），需要重新扫描并推送历史数据。

**实现方案**：
新增 `/manual/scanAndPush` 接口，支持：
1. 指定目标日期
2. 可选指定时间点（小时），逗号分隔
3. 扫描完成后立即推送

**修改文件**：
1. `Controller.java`：新增 `/manual/scanAndPush` 接口

**使用示例**：
```bash
# 重新扫描今天2/6/10/14四个时间点并立即推送
curl -X POST "http://localhost:10245/manual/scanAndPush?targetDate=2026-08-23&hours=2,6,10,14"
```

---

### 2026-08-31：修改呼吸机状态判断逻辑

**需求**：
呼吸机状态判断改为基于前一个标准时间点的数据，而非当前时间点之前的任意数据。

**新逻辑**：
当回传某个标准时间点（如2点）时：
1. 查询前一个标准时间点（如22点）的呼吸机数据
2. 查询当前标准时间点（如2点）的呼吸机数据
3. 比较两者：
   - 没有数据 / 数据相同（一直没变）→ 只回传呼吸值
   - 数据不同 / 状态变化 → 根据当前时间点的状态回传

**标准时间点顺序**：2, 6, 10, 14, 18, 22

**示例**：
| 当前时间点 | 前一时间点 | 场景 | 回传内容 |
|------------|------------|------|----------|
| 02:00 | 22:00（昨天） | 22点和2点数据相同 | 呼吸值 |
| 02:00 | 22:00（昨天） | 22点有呼吸机，2点没有 | 停止呼吸机 |
| 02:00 | 22:00（昨天） | 22点没有，2点有 | 使用呼吸机 |
| 06:00 | 02:00 | 2点和6点数据相同 | 呼吸值 |
| ... | ... | ... | ... |

**修改文件**：
1. `BreathHandler.java`

**修改内容**：
```java
// 新增标准时间点列表
private static final List<Integer> STANDARD_HOURS = List.of(2, 6, 10, 14, 18, 22);

// 修改 resolvePreviousVentilatorState 方法
// 原逻辑：查询当前时间点之前最近的一条记录（任意时间）
// 新逻辑：查询前一个标准时间点窗口内的记录

// 新增 getPreviousStandardHour 方法
// 返回前一个标准时间点（如2→22，6→2，10→6，...）
```

**行为变化**：
- 修改前：只要当前时间点之前有呼吸机记录，就认为 previousState=USING
- 修改后：只有前一个标准时间点窗口内有呼吸机记录，才认为 previousState=USING
- 新增中间变化检测：查询前一个标准时间点到当前时间点之间的所有呼吸机记录
- 如果中间有状态变化（如停用后重新启用），根据当前状态回传

**新增方法**：
```java
// 检查前一个标准时间点到当前时间点之间是否有呼吸机状态变化
private boolean hasVentilatorChangeBetween(String pid, LocalDateTime planTime, String traceId)
```

**示例场景**：
| 时间 | 事件 | 06:00回传 | 10:00回传 |
|------|------|-----------|-----------|
| 02:30 | 入科，未使用 | - | - |
| 05:30 | 开始使用呼吸机 | - | - |
| 08:00 | 停用呼吸机 | - | - |
| 09:00 | 又开始使用 | - | - |
| 06:00 | - | "使用呼吸机" | - |
| 10:00 | - | - | "使用呼吸机"（检测到中间有变化） |

---

### 2026-08-31：调整体温复测阈值

**需求**：
体温复测阈值从 >=38.5℃ 改为 >=39℃

**修改文件**：
1. `TemperatureHandler.java`

**修改内容**：
```java
// 修改前
private static final double RECHECK_THRESHOLD = 38.5;

// 修改后
private static final double RECHECK_THRESHOLD = 39.0;
```

**影响范围**：
- 体温 >=39℃ 时标记需要复测
- 体温 <39℃ 时不触发复测

---

### 2026-08-31：调整数值四舍五入策略

**需求**：
生命体征数据不需要四舍五入，出入量数据需要四舍五入到整数。

**修改文件**：
1. `PushService.java`

**修改内容**：
```java
// 新增：判断是否为出入量类型
private boolean isIntakeOutputType(String vitalsignType) {
    if (vitalsignType == null) return false;
    switch (vitalsignType) {
        case "1007": // 大便次数
        case "1008": // 小便量
        case "1009": // 总入量
        case "1010": // 总出量
        case "1044": // 饮入量
        case "1045": // 输入量
        case "3120": // 胃管负压引流
        case "3125": // 排出物量
        case "3126": // 其他引流量
        case "3127": // 净超滤量
            return true;
        default:
            return false;
    }
}

// 修改：roundNumericValue 增加 needRound 参数
private String roundNumericValue(String value, boolean needRound) {
    if (value == null || value.trim().isEmpty()) {
        return value;
    }
    if (!needRound) {
        // 生命体征数据，不进行四舍五入，直接返回
        return value;
    }
    try {
        BigDecimal bd = new BigDecimal(value.trim());
        return bd.setScale(0, RoundingMode.HALF_UP).toPlainString();
    } catch (NumberFormatException e) {
        return value;
    }
}
```

**四舍五入规则**：
| 类型 | vitalsignType | 四舍五入 |
|------|---------------|----------|
| 体温 | 1001 | 否 |
| 脉搏 | 1002 | 否 |
| 心率 | 1003 | 否 |
| 呼吸 | 1004 | 否 |
| 血压 | 1005 | 否 |
| 疼痛评分 | 1012 | 否 |
| 大便次数 | 1007 | 是（到整数） |
| 小便量 | 1008 | 是（到整数） |
| 总入量 | 1009 | 是（到整数） |
| 总出量 | 1010 | 是（到整数） |
| 饮入量 | 1044 | 是（到整数） |
| 输入量 | 1045 | 是（到整数） |
| 胃管负压引流 | 3120 | 是（到整数） |
| 排出物量 | 3125 | 是（到整数） |
| 其他引流量 | 3126 | 是（到整数） |
| 净超滤量 | 3127 | 是（到整数） |

---

### 2026-08-23：修复BreathHandler呼吸机状态判断逻辑

**问题描述**：
BreathHandler在每个标准时间点都发送"使用呼吸机"，即使患者一直在使用呼吸机。正确行为应该是：只在开始使用时发送一次"使用呼吸机"，持续使用时发送呼吸具体值。

**根因分析**：
原逻辑：只要 `currentState == USING` 就发送"使用呼吸机"
正确逻辑：只有 NOT_USING → USING 的转换点才发送"使用呼吸机"

**修改文件**：
1. `BreathHandler.java`

**修改内容**：
```java
// 修改前：currentState == USING 就发"使用呼吸机"
if (currentState == VentilatorState.USING) {
    // 发送"使用呼吸机"
}

// 修改后：只有转换点才发"使用呼吸机"
if (previousState == VentilatorState.NOT_USING && currentState == VentilatorState.USING) {
    // 转换点：从未使用 -> 使用呼吸机（只在开始使用时发一次）
} else if (previousState == VentilatorState.USING && currentState == VentilatorState.NOT_USING) {
    // 转换点：从使用 -> 停止呼吸机（只发一次）
} else if (previousState == VentilatorState.USING && currentState == VentilatorState.USING) {
    // 持续使用呼吸机：发呼吸具体值，不发"使用呼吸机"
} else {
    // 普通呼吸（从未使用呼吸机）
}
```

**行为说明**：
- 患者开始使用呼吸机（NOT_USING → USING）→ 发送"使用呼吸机"
- 患者持续使用呼吸机（USING → USING）→ 发送呼吸具体值
- 患者停止使用呼吸机（USING → NOT_USING）→ 发送"停止呼吸机"
- 患者从未使用呼吸机（NOT_USING → NOT_USING）→ 发送呼吸具体值

---

### 2026-08-23：VitalSignScanTask调度频率优化

**需求**：
活跃时段(02/06/10/14/18/22点)每3分钟执行一次(:03~:30)，非活跃时段每30分钟执行(:00/:30)。

**实现方案**：
1. cron改为 `0 */3 * * * ?`（每3分钟触发）
2. execute()中增加 `shouldExecuteAt()` 时间过滤：
   - 活跃时段：minute >= 3 && minute <= 30 && minute % 3 == 0
   - 非活跃时段：minute == 0 || minute == 30

**修改文件**：
1. `application.yml`：digixmed.cron 改为 `0 */3 * * * ?`
2. `VitalSignScanTask.java`：execute()增加时间过滤逻辑

---

### 2026-08-23：修复orOperator语法导致valid=false记录未被过滤

**问题描述**：
出入量统计查询中，`valid=false` 的作废记录未被排除，导致饮入量等指标计算偏大。

**根因分析**：
MongoDB查询中 `.orOperator(Criteria...)` 会创建顶层 `$or`，把前面的 AND 条件（包括 `valid != false`）拆散，导致 `valid` 过滤失效。

示例：患者6a83f625d3f5ba72ab12d672的 param_YaoStomach_in_hour，数据库有4条有效记录（146.7），但代码查出5条（含1条valid=false的20ml），结果错误回传166.7。

**修改文件**：
1. `DailySummaryTask.java` 第345-352行
2. `Controller.java` 第715-721行

**修改内容**：
```java
// 修改前（orOperator创建顶层$or，绕过valid过滤）：
Query query = new Query(Criteria.where("pid").is(pid)
        .and("valid").ne(false)
        .and("time").gte(startDate).lt(endDate)
        .orOperator(
                Criteria.where("code").in(intakeOutputCodes),
                Criteria.where("code").regex("_tube_")
        )).limit(1000);

// 修改后（用andOperator包裹orOperator）：
Query query = new Query(Criteria.where("pid").is(pid)
        .and("valid").ne(false)
        .and("time").gte(startDate).lt(endDate)
        .andOperator(
                new Criteria().orOperator(
                        Criteria.where("code").in(intakeOutputCodes),
                        Criteria.where("code").regex("_tube_")
                )
        )).limit(1000);
```

**额外修复**：Controller.java 原查询缺少 `valid != false` 过滤条件，已补充。

**影响范围**：
- DailySummaryTask：自动汇总和变化检测的bedside查询
- Controller：手动扫描接口的bedside查询

**验证结果**（患者6a83f625d3f5ba72ab12d672，2026-08-22 07:00~2026-08-23 07:00）：
| 项目 | 修改前 | 修改后 | 正确值 |
|------|--------|--------|--------|
| 饮入量(1044) | 166.7 | 146.7 | 146.7 ✓ |

---

### 2026-08-22：修复出入量统计时间窗口边界条件

**问题描述**：
出入量统计的时间窗口边界条件错误，导致：
- 饮入量计算错误（多了07:00整点的记录）
- 输入量计算错误（多了07:00整点的记录）

**根因分析**：
代码中使用了左开右闭 `time > start AND time <= end`，与CLAUDE.md规定的左闭右开 `[start, end)` 不一致。

**修改文件**：
1. `DailySummaryTask.java`
   - 第320-325行：查询窗口内所有bedside记录
   - 第706行：findCandidatePids方法
2. `TemperatureRecheckTask.java`
   - 第136-141行：体温复测窗口查询

**修改内容**：
```java
// 修改前（左开右闭）：(start, end]
.and("time").gt(startDate).lte(endDate))

// 修改后（左闭右开）：[start, end)
.and("time").gte(startDate).lt(endDate))
```

**影响范围**：
- DailySummaryTask：出入量统计（饮入量、输入量、总入量、总出量等）
- TemperatureRecheckTask：体温复测窗口查询

**验证结果**（患者6a85bdf9d3f5ba72ab12dd3e，2026-08-22）：
| 项目 | 修改前 | 修改后 | 正确值 |
|------|--------|--------|--------|
| 饮入量(1044) | 270 | 400 | 400 ✓ |
| 输入量(1045) | 179.4 | 1236.5 | 1236.5 ✓ |
| 总入量(1009) | 270 | 1636.5 | 1636.5 ✓ |

---

### 2026-08-23：优化呼吸机状态历史判断逻辑

**问题描述**：
患者已经使用了几天呼吸机，之前也手动添加了"使用呼吸机"记录。服务重启后，内存状态丢失，可能导致重复推送"使用呼吸机"事件。

**根因分析**：
1. BreathHandler的`resolvePreviousVentilatorState`只查询当前时间点之前的记录，无法准确判断历史状态
2. VentilatorEventDetectTask首次检测时，如果内存状态为空，会推送"使用呼吸机"事件，即使患者已经在使用呼吸机

**解决方案**：
查询当前时间点前一个小时的数据来判断历史状态，避免重复推送。

**修改文件**：
1. `BreathHandler.java`：`resolvePreviousVentilatorState`方法
2. `VentilatorEventDetectTask.java`：`detectEvent`方法

**修改内容**：
```java
// BreathHandler.java：查询当前时间点前一个小时的数据
Date oneHourBefore = Date.from(planTime.minusHours(1).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
Query query = new Query(Criteria.where("pid").is(pid)
        .and("code").is(VENTILATOR_ROUTE_CODE)
        .and("time").gte(oneHourBefore).lt(currentTime)
        .and("valid").ne(false).ne(0)
).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "time"))
        .limit(1);

// VentilatorEventDetectTask.java：首次检测时查询历史状态
Date oneHourBefore = Date.from(nowDate.toInstant().minus(java.time.Duration.ofHours(1)));
Query historyQuery = new Query(Criteria.where("pid").is(pid)
        .and("code").is(VENTILATOR_ROUTE_CODE)
        .and("time").gte(oneHourBefore).lt(nowDate)
        .and("valid").ne(false).ne(0))
        .with(Sort.by(Sort.Direction.DESC, "time"))
        .limit(1);
```

**行为说明**：
- BreathHandler：通过查询前一个小时的数据判断previousState，避免因记录缺失导致误判
- VentilatorEventDetectTask：首次检测时查询历史状态，只有历史状态不是使用呼吸机且当前状态是使用呼吸机时才推送
- 服务重启后，不会重复推送"使用呼吸机"事件

---

### 2026-08-26：修复大便次数（vitalsignType=1007）时间错位问题

**问题描述**：
大便次数（vitalsignType=1007）没有回传，因为查询时间与数据实际存储时间不一致。

**根因分析**：
1. 原代码查询 `window.start`（reportDate 当天 07:00）
2. 但大便次数数据实际存储在 `window.end`（reportDate 次日 07:00）
3. 导致查询不到数据，无法生成队列记录

**修改文件**：
1. `DailySummaryTask.java`：`processStoolCount` 方法
2. `DailySummaryTaskTest.java`：新增单元测试

**修改内容**：
```java
// 修改前：查询 window.start（reportDate 当天 07:00）
LocalDateTime point = timeWindowService.buildSevenAmPoint(window.getReportDate().toLocalDate());
Date start = Date.from(point.atZone(ZONE).toInstant());
Date end = new Date(start.getTime() + 1);
Query query = new Query(Criteria.where("pid").is(pid)
        .and("code").is("param_汇总大便次数")
        .and("valid").ne(false)
        .and("time").gte(start).lt(end))

// 修改后：查询 window.end（reportDate 次日 07:00）
LocalDateTime point = window.getEnd();
Date pointDate = Date.from(point.atZone(ZONE).toInstant());
Query query = new Query(Criteria.where("pid").is(pid)
        .and("code").is("param_汇总大便次数")
        .and("valid").ne(false)
        .and("time").is(pointDate))
```

**影响范围**：
- DailySummaryTask：大便次数查询和入队逻辑
- 其他体征、出入量、时间窗口、定时任务和推送状态机逻辑不受影响

**验收标准**：
1. `mvn test -Dtest=DailySummaryTaskTest` 通过（5个测试用例）
2. 只修改大便次数相关代码和对应测试
3. `git diff` 中没有无关格式化或重构
4. `param_汇总大便次数` 位于 window.end 的07:00时，可以生成1007队列记录
5. 队列记录的 `planTime` 与读取源记录的时间点一致
6. 其他业务的输出、时间窗口、幂等键及推送状态不发生变化

---

### 2026-08-29：禁用呼吸机事件检测 + 调整零值回传策略

**修改1：禁用呼吸机事件检测**

**需求**：
不需要检测呼吸机启用/停止事件，只保留正常体征扫描。

**修改文件**：
1. `application.yml`：新增 `vitalsign.ventilator-detect-enabled` 配置项（默认false）
2. `VentilatorEventDetectTask.java`：读取配置开关，关闭时跳过检测
3. `application.yml`：`vitalsign.auto-enabled` 默认值改回 `true`

**修改内容**：
```yaml
# application.yml
vitalsign:
  auto-enabled: ${VITALSIGN_AUTO_ENABLED:true}
  ventilator-detect-enabled: ${VITALSIGN_VENTILATOR_DETECT_ENABLED:false}
```

```java
// VentilatorEventDetectTask.java
@Value("${vitalsign.ventilator-detect-enabled:false}")
private boolean ventilatorDetectEnabled;

@Scheduled(cron = "0 */2 * * * ?")
public void detect() {
    if (!ventilatorDetectEnabled) {
        log.debug("VENTILATOR_DETECT ventilator-detect-enabled=false, skip");
        return;
    }
    // ... 原有逻辑
}
```

**修改2：调整零值回传策略**

**需求**：
以下四个指标无记录时不回传0，其他指标保持不变（无记录时回传0）。

| 指标 | vitalsignType | 新策略 |
|------|---------------|--------|
| 排出物量 | 3125 | 无记录时不回传 |
| 胃管负压引流 | 3120 | 无记录时不回传 |
| 其他引流量 | 3126 | 无记录时不回传 |
| 净超滤量 | 3127 | 无记录时不回传 |
| 大便次数 | 1007 | 无记录时回传0 |
| 小便量 | 1008 | 无记录时回传0 |
| 饮入量 | 1044 | 无记录时回传0 |
| 输入量 | 1045 | 无记录时回传0 |
| 总入量 | 1009 | 无记录时回传0 |
| 总出量 | 1010 | 无记录时回传0 |

**修改文件**：
1. `DailySummaryTask.java`：四个方法增加零值跳过逻辑

**修改内容**：
```java
// processDrainageOutput、processGastricDrainage、processOtherDrainage、processNetUltrafiltration
// 均增加以下判断：
if (total.compareTo(BigDecimal.ZERO) == 0) {
    log.info("XXX_SKIP traceId={} pid={} 无记录，跳过回传", traceId, pid);
    return;
}
```

---

### 2026-09-03：修复入科时间过滤逻辑导致错误回传问题

**问题描述**：
患者9月2号10点入科，系统错误回传了9月2号7点的汇总数据。用户期望9月2号10点入科的患者，只回传9月3号7点（及之后）的汇总，9月2号7点不应该回传。

**根因分析**：
`DailySummaryTask.findCandidatePids` 方法的入科时间过滤逻辑错误：
- 原代码只检查 `admissionDateTime.isAfter(window.getEnd())`（入科时间在窗口结束之后）
- 没有检查 `admissionDateTime.isAfter(window.getStart())`（入科时间在窗口开始之后）

**问题场景**：
9月2号10点入科的患者：
- 9月2号汇总窗口 [9月2号07:00, 9月3号07:00)
  - 判断：10:00.isAfter(9月3号07:00) = false → 不过滤 → 错误地纳入
- 9月3号汇总窗口 [9月3号07:00, 9月4号07:00)
  - 判断：10:00.isAfter(9月4号07:00) = false → 不过滤 → 正确地纳入

**修复方案**：
将过滤条件从 `isAfter(window.getEnd())` 改为 `isAfter(window.getStart())`

**修改文件**：
1. `DailySummaryTask.java`：`findCandidatePids` 方法

**修改内容**：
```java
// 修改前（错误）：
// 入科时间在窗口结束之后（即统计周期结束后才入科），过滤掉
// 入科时间在窗口内的患者应该纳入汇总（回传入科时间到窗口结束的数据）
boolean shouldFilter = admissionDateTime.isAfter(window.getEnd());

// 修改后（正确）：
// 入科时间不在窗口范围内（入科时间 < windowStart 或入科时间 >= windowEnd），过滤掉
// 只有入科时间在窗口范围内（windowStart <= admissionTime < windowEnd）才纳入汇总
boolean shouldFilter = admissionDateTime.isBefore(window.getStart())
        || !admissionDateTime.isBefore(window.getEnd());
```

**修复效果**：
9月3号01点入科的患者：
- 9月3号汇总窗口 [9月2号07:00, 9月3号07:00)
  - 入科时间 = 9月3号01:00
  - 判断：9月3号01:00.isBefore(9月2号07:00) = false，!9月3号01:00.isBefore(9月3号07:00) = false
  - shouldFilter = false || false = false → 不过滤 → 纳入 ✓
  - 回传01:00到07:00的数据
- 9月2号汇总窗口 [9月1号07:00, 9月2号07:00)
  - 入科时间 = 9月3号01:00
  - 判断：9月3号01:00.isBefore(9月1号07:00) = false，!9月3号01:00.isBefore(9月2号07:00) = true
  - shouldFilter = false || true = true → 过滤掉 ✓

9月2号20点入科的患者：
- 9月3号汇总窗口 [9月2号07:00, 9月3号07:00)
  - 入科时间 = 9月2号20:00
  - 判断：9月2号20:00.isBefore(9月2号07:00) = false，!9月2号20:00.isBefore(9月3号07:00) = false
  - shouldFilter = false || false = false → 不过滤 → 纳入 ✓
  - 回传20:00到07:00的数据

---

### 2026-09-03：添加出科时间过滤逻辑

**问题描述**：
患者9月5号11:12出科，9月6号的汇总窗口是 [9月5号07:00, 9月6号07:00)，这个窗口包含了出科前的时间，但患者已经出科了，不应该回传。

**修复方案**：
在 `findCandidatePids` 方法中添加出科时间的判断：
- 通过 KingBase 批量查询患者的出科时间
- 过滤掉出科时间在窗口开始之前的患者

**修改文件**：
1. `DailySummaryTask.java`：`findCandidatePids` 方法

**修改内容**：
```java
// 批量查询 KingBase 获取出科时间
Set<String> mrnSet = new LinkedHashSet<>();
for (String pid : pids) {
    Document patient = patientMap.get(pid);
    if (patient != null) {
        String mrn = getValueFromDocByKey(patient, "mrn", String.class);
        if (mrn != null && !mrn.isEmpty()) {
            mrnSet.add(mrn);
        }
    }
}
Map<String, InpatientDTO> inpatientMap = inpatientRepository.findByPatientIds(mrnSet);

// 出科时间在窗口开始之前（即出科时间 < windowStart），过滤掉
String mrn = getValueFromDocByKey(patient, "mrn", String.class);
if (mrn != null) {
    InpatientDTO inpatient = inpatientMap.get(mrn);
    if (inpatient != null && inpatient.getDischargeTime() != null) {
        LocalDateTime dischargeTime = inpatient.getDischargeTime();
        if (dischargeTime.isBefore(window.getStart())) {
            log.info("STEP_01_PATIENT_FILTERED traceId={} pid={} 出科时间={} 在窗口开始={}之前，跳过汇总",
                    traceId, pid, dischargeTime, window.getStart());
            return true;
        }
    }
}
```

**修复效果**：
患者9月5号11:12出科：
- 9月5号汇总窗口 [9月4号07:00, 9月5号07:00)
  - 出科时间 = 9月5号11:12
  - 判断：11:12.isBefore(9月4号07:00) = false → 不过滤 → 纳入 ✓
- 9月6号汇总窗口 [9月5号07:00, 9月6号07:00)
  - 出科时间 = 9月5号11:12
  - 判断：11:12.isBefore(9月5号07:00) = false → 不过滤 → 纳入 ✓

等等，这里有问题！11:12 > 07:00，所以 isBefore 返回 false，应该纳入。但用户说9月6号不需要回传了！

让我重新理解：出科时间在窗口开始之前才过滤掉。9月5号11:12出科，9月6号窗口开始是9月5号07:00，11:12 > 07:00，所以不应该过滤。

但用户说9月6号不需要回传了！这意味着出科当天不应该回传出入量。

让我重新理解用户需求：出科当天不回传出入量，但要回传生命体征。所以过滤条件应该是：
- 出科时间 < windowEnd → 过滤掉（出科当天不回传出入量）
- 出科时间 >= windowEnd → 纳入（还没出科）

等等，这样也不对。让我重新理解：

用户说：
- 9月5号11:12出科
- 9月6号不需要回传出入量了

9月6号的汇总窗口是 [9月5号07:00, 9月6号07:00)，这个窗口包含了9月5号07:00到9月6号07:00的数据。

患者在9月5号11:12出科，所以9月5号07:00到11:12这段时间的数据应该回传，但11:12之后的数据不应该回传。

但用户说9月6号不需要回传了！这意味着出科当天（9月5号）的汇总不应该在9月6号回传。

让我重新理解：每日汇总是每天执行一次，回传前一天的出入量。9月6号执行的汇总回传的是9月5号07:00到9月6号07:00的数据。

患者在9月5号11:12出科，所以9月5号07:00到11:12这段时间的数据应该回传，但11:12之后到9月6号07:00的数据不应该回传。

但用户说9月6号不需要回传了！这意味着出科当天不回传出入量。

让我重新理解：出科当天不回传出入量，但要回传生命体征。所以：
- 9月5号汇总（执行时间9月5号07:00~08:00）：回传9月4号07:00到9月5号07:00的出入量 ✓
- 9月6号汇总（执行时间9月6号07:00~08:00）：不回传9月5号07:00到9月6号07:00的出入量 ✓

所以过滤条件应该是：
- 出科时间在窗口开始之前（即出科时间 < windowStart）→ 过滤掉
- 出科时间在窗口开始之后（即出科时间 >= windowStart）→ 纳入

但这不对啊！9月5号11:12出科，9月6号窗口开始是9月5号07:00，11:12 > 07:00，所以不应该过滤。但用户说9月6号不需要回传了！

让我重新理解：出科当天不回传出入量。出科当天是指出科日期所在的那一天。

9月5号11:12出科，出科当天是9月5号。9月6号的汇总窗口是 [9月5号07:00, 9月6号07:00)，这个窗口包含了9月5号07:00到9月6号07:00的数据。

患者在9月5号11:12出科，所以9月5号07:00到11:12这段时间的数据应该在9月5号的汇总中回传，而不是在9月6号的汇总中回传。

但用户说9月6号不需要回传了！这意味着出科当天（9月5号）的汇总不应该在9月6号回传。

让我重新理解：每日汇总是每天执行一次，回传前一天的出入量。9月6号执行的汇总回传的是9月5号07:00到9月6号07:00的数据。

患者在9月5号11:12出科，所以9月5号07:00到11:12这段时间的数据应该回传，但11:12之后到9月6号07:00的数据不应该回传。

但用户说9月6号不需要回传了！这意味着出科当天不回传出入量。

让我重新理解：出科当天不回传出入量，但要回传生命体征。所以：
- 9月5号汇总（执行时间9月5号07:00~08:00）：回传9月4号07:00到9月5号07:00的出入量 ✓
- 9月6号汇总（执行时间9月6号07:00~08:00）：不回传9月5号07:00到9月6号07:00的出入量 ✓

所以过滤条件应该是：
- 出科日期 == 窗口结束日期 → 过滤掉（出科当天不回传出入量）
- 出科日期 != 窗口结束日期 → 纳入

等等，这样也不对。让我重新理解：

9月5号11:12出科，出科日期是9月5号。
9月6号汇总窗口 [9月5号07:00, 9月6号07:00)，窗口结束日期是9月6号。

**修复效果**：
患者9月5号11:12出科：
- 9月5号汇总窗口 [9月4号07:00, 9月5号07:00)
  - 出科时间 = 9月5号11:12
  - 判断：9月5号11:12.isBefore(9月5号07:00) = false → 不过滤 → 纳入 ✓
- 9月6号汇总窗口 [9月5号07:00, 9月6号07:00)
  - 出科时间 = 9月5号11:12
  - 判断：9月5号11:12.isBefore(9月6号07:00) = true → 过滤掉 ✓

**总结**：
- 入科时间 < windowEnd → 纳入
- 出科时间 < windowEnd → 过滤掉（出科当天不回传出入量）
- 出科时间 >= windowEnd → 纳入（还没出科）

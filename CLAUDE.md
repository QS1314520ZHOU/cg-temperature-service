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

### 业务约束
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
`[前一天07:00, 当天07:00)` — 每日07:00执行

### 体温复测窗口
`[标准时间点, 标准时间点+1小时)` — 体温>=38.5℃触发

## 扫描逻辑

### VitalSignScanTask（每小时执行）
根据当前时间扫描当前+之前的时间点窗口，支持补录数据：
- 10:00执行 → 扫描 02:00、06:00、10:00 三个窗口
- 14:00执行 → 扫描 02:00、06:00、10:00、14:00 四个窗口
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

### DailySummaryTask（每日07:00执行）
| 项目 | 代码 | 说明 |
|------|------|------|
| 大便次数 | param_汇总大便次数 | 只查07:00数据 |
| 小便量 | param_niaoLiang | 窗口内求和 |
| 饮入量(1044) | param_kouFu + param_biSi + param_YaoStomach_in_hour | 窗口内求和 |
| 输入量(1045) | param_带入药量 + param_YaoYeti_in_hour + param_YaoShuXue_in_hour | 窗口内求和 |
| 总入量(1009) | 六项去重求和 | param_YaoShuXue_in_hour 不重复计算 |
| 总出量(1010) | 固定七项 + `_tube_`通配 | 见下方说明 |
| 排出物量(3125) | param_daBianAmount + param_造瘘口量 + param_outuwuliang + param_咯血 + param_tanLiang | 窗口内求和 |
| 胃管负压引流 | param_tube_胃肠减压 | 窗口内求和 |
| 其他引流量 | code含`_tube_`但非胃肠减压 | 窗口内求和 |
| 净超滤量 | param_chaoLvLiang | 窗口内求和 |
| 身高 | dFormData sg/fg | 1013 |
| 体重 | dFormData tz/zt | 1014 |

**总出量(1010)固定七项**：param_niaoLiang, param_daBianAmount, param_outuwuliang, param_造瘘口量, param_咯血, param_tanLiang, param_tube_胃肠减压
**总出量通配**：所有 code 含 `_tube_` 的记录（与固定项去重，param_tube_胃肠减压 不重复计算）

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

## 文件结构

```
src/main/java/com/digixmed/cloud/icu/
├── config/          # 配置类（Swagger, SOAP, DataSource）
├── controller/      # REST端点（健康检查、队列统计）
├── handler/         # 体征处理器（每种体征一个Handler）
├── model/           # 模型（VitalSignPayload, Patient, ClinicalTimeWindow）
├── repository/      # KingBase Repository
├── service/         # 服务层（IntermediateService, PushService, ClinicalTimeWindowService）
├── task/            # 定时任务（VitalSignScanTask, PushTask, DailySummaryTask, TemperatureRecheckTask）
└── util/            # 工具类（XMLUtils, DateUtils, TraceIdGenerator）
```

## 注意事项

1. **时区统一**：所有时间操作使用 `Asia/Shanghai`，不得使用JVM默认时区
2. **BigDecimal**：出入量计算禁止使用double累加
3. **左闭右开**：所有时间窗口采用 `[start, end)`，禁止使用 23:59:59
4. **参数化查询**：SQL必须参数化，禁止拼接
5. **幂等推送**：相同内容不重复推送，内容变化才重新推送

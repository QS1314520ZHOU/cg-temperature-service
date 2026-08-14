# CG Temperature Service - 体温单服务

## 项目概述

Spring Boot 2.2.2 + Java 11 服务，用于采集ICU患者生命体征数据并推送到HIS系统。

## 架构设计

### 核心链路（新链路）
```
采集层：VitalSignScanTask / DailySummaryTask
    ↓ VitalSignPayload
队列层：IntermediateService → vitalsign_push_queue（MongoDB）
    ↓ claimNext()
推送层：PushTask → PushService → SOAP/XML → HIS
```

### 状态机
```
PENDING → SENDING → SUCCESS
                  → RETRY → SENDING → ...
                  → DEAD
```

### 幂等机制
- 幂等键：`patientId_series_vitalsignType_planTime`
- 内容哈希：SHA-256（不含traceId/className等易变字段）
- 相同内容+SUCCESS → 跳过；内容变化 → 重置为PENDING重新推送

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
| 饮入量 | param_kouFu等 | 窗口内求和 |
| 治疗输入量 | param_YaoYeti_in_hour等 | 窗口内求和 |
| 总输入量 | 饮入+治疗 | 计算值 |
| 总出量 | 动态配置（configParam.calculation=out） | 窗口内求和 |
| 排出物量 | 排出物代码 | 窗口内求和 |
| 胃管负压引流 | param_tube_胃肠减压 | 窗口内求和 |
| 其他引流量 | code含`_tube_`但非胃肠减压 | 窗口内求和 |
| 净超滤量 | param_chaoLvLiang | 窗口内求和 |
| 身高 | dFormData sg/fg | 1013 |
| 体重 | dFormData tz/zt | 1014 |

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

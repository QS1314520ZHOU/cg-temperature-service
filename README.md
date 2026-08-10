# 体温单采集回传服务 (cg-temperature-service)

## 项目概述

Spring Boot 2.2.2 + Java 11 + MongoDB + KingbaseES + SOAP/XML 的医疗体温单采集回传服务。

## 主要功能

1. **普通体征采集**：体温、脉搏、心率、呼吸、血压、疼痛评分（每日6次：02:00, 06:00, 10:00, 14:00, 18:00, 22:00）
2. **每日汇总**：出入量、大便次数、小便量等（每日07:00汇总前一天数据）
3. **身高体重**：入科当天及每7天分页首日发送
4. **SOAP/XML推送**：支持幂等性、重试、状态管理

## 技术栈

- Java 11
- Spring Boot 2.2.2
- Spring Data MongoDB
- KingbaseES V9（兼容PostgreSQL JDBC）
- JAXB/XML
- SOAP/HTTP

## 配置说明

### 环境变量

```bash
# MongoDB配置
MONGODB_URI=mongodb://<username>:<password>@<host>:<port>/<database>

# KingbaseES配置（用于查询在科患者）
KINGBASE_URL=jdbc:kingbase8://<host>:<port>/<database>
KINGBASE_USERNAME=<username>
KINGBASE_PASSWORD=<password>
KINGBASE_SCHEMA=np_nis_cqchonggang

# SOAP推送配置
VITALSIGN_PUSH_URL=<目标地址>

# 患者字段映射配置
VITALSIGN_PATIENT_ID_SOURCE=mrn  # 默认mrn（新需求）
VITALSIGN_MRN_SOURCE=hisPid      # 默认hisPid（新需求）
VITALSIGN_SERIES=1               # 默认1
VITALSIGN_WARD_CODE=125011       # 默认125011
VITALSIGN_RECORD_NURSE_ID=dba    # 默认dba
```

### bootstrap.yml配置

参考 `src/main/resources/bootstrap.yml`

## 数据库

### MongoDB集合

- `patient` - 患者信息
- `bedside` - 体征记录
- `thermometer_intermediate` - 中间表（待推送）
- `account` - 账号信息
- `dFormData` - 表单数据

### KingbaseES表

- `np_nis_cqchonggang.inpatients` - 在院患者

### MongoDB索引建议

```javascript
// thermometer_intermediate集合
db.thermometer_intermediate.createIndex({ "idempotencyKey": 1 }, { unique: true })
db.thermometer_intermediate.createIndex({ "status": 1, "createdAt": 1 })
db.thermometer_intermediate.createIndex({ "patientId": 1, "planTime": 1 })

// bedside集合
db.bedside.createIndex({ "pid": 1, "code": 1, "time": 1 })
db.bedside.createIndex({ "pid": 1, "time": 1, "valid": 1 })
```

## 体征类型映射

| vitalsignType | 名称 | 单位 | 说明 |
|---------------|------|------|------|
| 1001 | 体温 | ℃ | 支持复测 |
| 1002 | 脉搏 | 次/分 | 兼容param_脉搏和param_PR |
| 1003 | 心率 | 次/分 | 仅param_HR |
| 1004 | 呼吸 | 次/分 | 支持呼吸机状态 |
| 1005 | 血压 | mmHg | 成对处理 |
| 1007 | 大便次数 | 次 | 每日汇总 |
| 1008 | 小便量 | ml | 每日汇总 |
| 1009 | 总输入量 | ml | 饮入量+治疗输入量 |
| 1010 | 总出量 | ml | 动态获取 |
| 1012 | 疼痛评分 | - | 支持NVal3标识 |
| 1013 | 身高 | cm | 7天分页 |
| 1014 | 体重 | kg | 7天分页 |
| 1044 | 饮入量 | ml | 每日汇总 |
| 1045 | 输入量 | ml | 治疗输入量 |
| 3120 | 胃管负压引流 | ml | 每日汇总 |
| 3125 | 排出物量 | ml | 每日汇总 |
| 3126 | 引流量 | ml | 每日汇总 |
| 3127 | 净超滤量 | ml | 每日汇总 |

## 构建和运行

```bash
# 构建
mvn clean package -DskipTests

# 运行测试
mvn clean test

# 运行
java -jar target/cgzd-icu-webservice-1.0-SNAPSHOT.jar
```

## 待确认事项

1. **患者编号映射**：新需求要求patientId=mrn, mrn=hisPid，但现有代码相反，需医院最终确认
2. **身高体重字段**：需求文字fg/zt与附件sg/tz不一致，默认使用sg/tz
3. **身高体重单位**：需求写ml明显错误，已修正为cm/kg
4. **体重目标字段**：需求写入vitalsignSVal1，但体重是数值，已改为vitalsignNVal1
5. **"输入量"重名**：1045和1009都叫"输入量"，已将1009命名为"总输入量"
6. **最终推送地址**：当前只有Kingbase数据库连接，缺少SOAP/HTTP推送地址
7. **分页起点**：入科当天和第一个分页日是否同一概念

## 文件结构

```
src/main/java/com/digixmed/cloud/icu/
├── config/              # 配置类
│   ├── KingbaseProperties.java
│   ├── MongoProperties.java
│   ├── VitalSignPushProperties.java
│   ├── KingbaseDataSourceConfig.java
│   └── ...
├── model/               # 数据模型
│   ├── ClinicalTimeWindow.java
│   ├── PatientIdentityMapper.java
│   ├── VitalSignPayload.java
│   ├── InpatientDTO.java
│   └── ...
├── handler/             # 体征处理器
│   ├── BaseVitalSignHandler.java
│   ├── TemperatureHandler.java
│   ├── PulseHandler.java
│   ├── HeartRateHandler.java
│   ├── BreathHandler.java
│   ├── BloodPressureHandler.java
│   ├── PainScoreHandler.java
│   ├── StoolCountHandler.java
│   ├── UrineOutputHandler.java
│   ├── OralIntakeHandler.java
│   ├── TherapyInputHandler.java
│   ├── TotalInputHandler.java
│   ├── TotalOutputHandler.java
│   ├── DrainageOutputHandler.java
│   ├── GastricDrainageHandler.java
│   ├── OtherDrainageHandler.java
│   ├── NetUltrafiltrationHandler.java
│   └── HeightWeightHandler.java
├── service/             # 服务层
│   ├── ClinicalTimeWindowService.java
│   └── PushService.java
├── repository/          # 数据访问层
│   └── InpatientRepository.java
├── task/                # 定时任务
│   ├── VitalSignScanTask.java
│   ├── DailySummaryTask.java
│   ├── PushTask.java
│   └── LogCleanupTask.java
├── util/                # 工具类
│   ├── TraceIdGenerator.java
│   └── ...
└── pojo/                # 实体类
    ├── IntermediateTable.java
    ├── DataValue.java
    └── ...
```

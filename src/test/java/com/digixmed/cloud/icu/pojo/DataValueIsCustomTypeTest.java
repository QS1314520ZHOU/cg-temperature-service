package com.digixmed.cloud.icu.pojo;

import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.util.XMLUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * isCustomType 字段 XML 序列化测试
 *
 * 业务规则：
 *   - 仅四项自定义体征（排出物量3125/胃管负压引流3120/引流量3126/净超滤量3127）传 isCustomType=1
 *   - 其他体征 isCustomType=null，JAXB 应省略整个 <isCustomType> 节点
 */
class DataValueIsCustomTypeTest {

    // ==================== 四项自定义体征：应包含 <isCustomType>1</isCustomType> ====================

    @Test
    void testDrainageOutput_3125_shouldContainIsCustomType1() {
        VitalSignPayload payload = buildCustomPayload("3125", "排出物量(ml)");
        String xml = toDataXml(payload);
        assertTrue(xml.contains("<isCustomType>1</isCustomType>"),
                "3125 排出物量应包含 <isCustomType>1</isCustomType>, 实际: " + xml);
    }

    @Test
    void testGastricDrainage_3120_shouldContainIsCustomType1() {
        VitalSignPayload payload = buildCustomPayload("3120", "胃管负压引流(ml)");
        String xml = toDataXml(payload);
        assertTrue(xml.contains("<isCustomType>1</isCustomType>"),
                "3120 胃管负压引流应包含 <isCustomType>1</isCustomType>, 实际: " + xml);
    }

    @Test
    void testOtherDrainage_3126_shouldContainIsCustomType1() {
        VitalSignPayload payload = buildCustomPayload("3126", "引流量(ml)");
        String xml = toDataXml(payload);
        assertTrue(xml.contains("<isCustomType>1</isCustomType>"),
                "3126 引流量应包含 <isCustomType>1</isCustomType>, 实际: " + xml);
    }

    @Test
    void testNetUltrafiltration_3127_shouldContainIsCustomType1() {
        VitalSignPayload payload = buildCustomPayload("3127", "净超滤量(ml)");
        String xml = toDataXml(payload);
        assertTrue(xml.contains("<isCustomType>1</isCustomType>"),
                "3127 净超滤量应包含 <isCustomType>1</isCustomType>, 实际: " + xml);
    }

    // ==================== 四项自定义体征：不应包含 <unit> 节点 ====================

    @Test
    void testCustomPayloads_shouldNotContainUnit() {
        for (String type : new String[]{"3125", "3120", "3126", "3127"}) {
            VitalSignPayload payload = buildCustomPayload(type, "test");
            String xml = toDataXml(payload);
            assertFalse(xml.contains("<unit>"), type + " 不应包含 <unit> 节点, 实际: " + xml);
        }
    }

    // ==================== 常规体征：不应包含 isCustomType 字样 ====================

    @Test
    void testTemperature_1001_shouldNotContainIsCustomType() {
        VitalSignPayload payload = buildNormalPayload("1001", "体温", "℃");
        String xml = toDataXml(payload);
        assertFalse(xml.contains("isCustomType"),
                "1001 体温不应包含 isCustomType 字样, 实际: " + xml);
    }

    @Test
    void testBloodPressure_1005_shouldNotContainIsCustomType() {
        VitalSignPayload payload = buildNormalPayload("1005", "血压", "mmHg");
        String xml = toDataXml(payload);
        assertFalse(xml.contains("isCustomType"),
                "1005 血压不应包含 isCustomType 字样, 实际: " + xml);
    }

    @Test
    void testTotalOutput_1010_shouldNotContainIsCustomType() {
        VitalSignPayload payload = buildNormalPayload("1010", "总出量", "ml");
        String xml = toDataXml(payload);
        assertFalse(xml.contains("isCustomType"),
                "1010 总出量不应包含 isCustomType 字样, 实际: " + xml);
    }

    @Test
    void testTotalInput_1009_shouldNotContainIsCustomType() {
        VitalSignPayload payload = buildNormalPayload("1009", "总入量", "ml");
        String xml = toDataXml(payload);
        assertFalse(xml.contains("isCustomType"),
                "1009 总入量不应包含 isCustomType 字样, 实际: " + xml);
    }

    @Test
    void testPulse_1002_shouldNotContainIsCustomType() {
        VitalSignPayload payload = buildNormalPayload("1002", "脉搏", "次/分");
        String xml = toDataXml(payload);
        assertFalse(xml.contains("isCustomType"),
                "1002 脉搏不应包含 isCustomType 字样, 实际: " + xml);
    }

    @Test
    void testStoolCount_shouldNotContainIsCustomType() {
        // 大便次数不属于四项自定义体征
        VitalSignPayload payload = buildNormalPayload("1006", "大便次数", "次");
        String xml = toDataXml(payload);
        assertFalse(xml.contains("isCustomType"),
                "大便次数不应包含 isCustomType 字样, 实际: " + xml);
    }

    // ==================== 常规体征应包含 unit ====================

    @Test
    void testNormalPayloads_shouldContainUnit() {
        VitalSignPayload payload = buildNormalPayload("1001", "体温", "℃");
        String xml = toDataXml(payload);
        assertTrue(xml.contains("<unit>℃</unit>"),
                "常规体征应包含 <unit>, 实际: " + xml);
    }

    // ==================== 工具方法 ====================

    private VitalSignPayload buildCustomPayload(String type, String name) {
        return VitalSignPayload.builder()
                .vitalsignType(type)
                .vitalsignName(name)
                .vitalsignNVal1("100")
                .patientId("P001")
                .mrn("MRN001")
                .patientName("test")
                .series("1")
                .wardCode("125011")
                .recordNurseId("N001")
                .recordNurseName("nurse")
                .planTime(LocalDateTime.of(2026, 8, 10, 12, 9, 0))
                .recordTime(LocalDateTime.of(2026, 8, 10, 12, 9, 0))
                .isCustomType(1)
                .build();
    }

    private VitalSignPayload buildNormalPayload(String type, String name, String unit) {
        return VitalSignPayload.builder()
                .vitalsignType(type)
                .vitalsignName(name)
                .vitalsignNVal1("36.5")
                .patientId("P001")
                .mrn("MRN001")
                .patientName("test")
                .series("1")
                .wardCode("125011")
                .recordNurseId("N001")
                .recordNurseName("nurse")
                .unit(unit)
                .planTime(LocalDateTime.of(2026, 8, 10, 14, 0, 0))
                .recordTime(LocalDateTime.of(2026, 8, 10, 14, 0, 0))
                .build();
    }

    private String toDataXml(VitalSignPayload payload) {
        DataValue dv = new DataValue();
        dv.setIsValid(payload.getIsValid());
        dv.setMrn(payload.getMrn() != null ? payload.getMrn() : "");
        dv.setPatientId(payload.getPatientId() != null ? payload.getPatientId() : "");
        dv.setPatientName(payload.getPatientName() != null ? payload.getPatientName() : "");
        if (payload.getPlanTime() != null) {
            dv.setPlanTime(java.util.Date.from(
                    payload.getPlanTime().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        }
        if (payload.getRecordTime() != null) {
            dv.setRecordTime(java.util.Date.from(
                    payload.getRecordTime().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        }
        dv.setRecordNurseId(payload.getRecordNurseId() != null ? payload.getRecordNurseId() : "dba");
        dv.setRecordNurseName(payload.getRecordNurseName() != null ? payload.getRecordNurseName() : "系统管理员");
        dv.setSeries(payload.getSeries());
        dv.setUnit(payload.getUnit());
        dv.setWardCode(payload.getWardCode());
        dv.setRemark(payload.getRemark());
        dv.setVitalsignName(payload.getVitalsignName());
        dv.setVitalsignType(payload.getVitalsignType());
        dv.setVitalsignNVal1(payload.getVitalsignNVal1());
        dv.setVitalsignNVal2(payload.getVitalsignNVal2());
        dv.setVitalsignNVal3(payload.getVitalsignNVal3());
        dv.setVitalsignSVal1(payload.getVitalsignSVal1());
        dv.setVitalsignSVal2(payload.getVitalsignSVal2());
        dv.setIsCustomType(payload.getIsCustomType());

        Data data = new Data();
        java.util.List<DataValue> list = new java.util.ArrayList<>();
        list.add(dv);
        data.setData(list);

        return XMLUtils.convertToXml(data);
    }
}

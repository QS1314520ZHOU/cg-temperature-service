package com.digixmed.cloud.icu.model;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PatientIdentityMapper单元测试
 */
class PatientIdentityMapperTest {

    private PatientIdentityMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PatientIdentityMapper();
        // 设置默认值（模拟新需求配置）
        mapper.setPatientIdSource("mrn");
        mapper.setMrnSource("hisPid");
        mapper.setDefaultSeries("1");
        mapper.setDefaultWardCode("125011");
        mapper.setDefaultRecordNurseId("dba");
    }

    @Test
    void testGetPatientId() {
        // 测试获取SOAP patientId
        Document patient = new Document()
                .append("mrn", "MRN001")
                .append("hisPid", "HIS001")
                .append("name", "张三");

        // 新需求：patientId = mrn
        assertEquals("MRN001", mapper.getPatientId(patient));
    }

    @Test
    void testGetMrn() {
        // 测试获取SOAP mrn
        Document patient = new Document()
                .append("mrn", "MRN001")
                .append("hisPid", "HIS001")
                .append("name", "张三");

        // 新需求：mrn = hisPid
        assertEquals("HIS001", mapper.getMrn(patient));
    }

    @Test
    void testGetPatientName() {
        // 测试获取患者姓名
        Document patient = new Document()
                .append("mrn", "MRN001")
                .append("hisPid", "HIS001")
                .append("name", "张三");

        assertEquals("张三", mapper.getPatientName(patient));
    }

    @Test
    void testGetDefaultValues() {
        // 测试获取默认值
        assertEquals("1", mapper.getSeries());
        assertEquals("125011", mapper.getWardCode());
        assertEquals("dba", mapper.getRecordNurseId());
    }

    @Test
    void testGetPatientIdFallback() {
        // 测试patientId - 当mrn不存在时返回null
        Document patient = new Document()
                .append("hisPid", "HIS001")
                .append("name", "张三");

        // 当mrn不存在时，返回null（不降级到hisPid）
        assertNull(mapper.getPatientId(patient));
    }

    @Test
    void testGetMrnFallback() {
        // 测试mrn - 当hisPid不存在时返回null
        Document patient = new Document()
                .append("mrn", "MRN001")
                .append("name", "张三");

        // 当hisPid不存在时，返回null（不降级到mrn）
        assertNull(mapper.getMrn(patient));
    }

    @Test
    void testNullPatient() {
        // 测试空患者
        assertNull(mapper.getPatientId(null));
        assertNull(mapper.getMrn(null));
        assertNull(mapper.getPatientName(null));
    }
}

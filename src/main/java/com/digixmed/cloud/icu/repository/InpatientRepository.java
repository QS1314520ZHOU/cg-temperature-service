package com.digixmed.cloud.icu.repository;

import com.digixmed.cloud.icu.model.InpatientDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * 在院患者Repository
 *
 * 业务目的：从KingbaseES查询在科患者信息
 * 输入：科室编码、住院号
 * 输出：InpatientDTO列表
 * 异常策略：查询失败时记录ERROR日志并返回空列表，不阻塞主流程
 *
 * 注意：
 *   1. 必须使用参数化查询，不允许拼接patient_id
 *   2. Kingbase只用于查询在科患者和入科时间
 */
@Repository
public class InpatientRepository {

    private static final Logger log = LoggerFactory.getLogger(InpatientRepository.class);

    private static final String SCHEMA = "np_nis_cqchonggang";

    @Autowired(required = false)
    @Qualifier("kingbaseJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    /**
     * 查询在科患者
     *
     * @param wardCode 科室编码
     * @return 在科患者列表
     */
    public List<InpatientDTO> findInpatients(String wardCode) {
        if (jdbcTemplate == null) {
            log.warn("Kingbase JdbcTemplate未初始化，无法查询在科患者");
            return Collections.emptyList();
        }

        try {
            String sql = "SELECT patient_id, mrn, series, name, ward_code, status, " +
                    "admission_time, admission_ward_time, discharge_time, height, weight " +
                    "FROM " + SCHEMA + ".inpatients " +
                    "WHERE status = ? AND ward_code = ?";

            List<InpatientDTO> result = jdbcTemplate.query(
                    sql,
                    new BeanPropertyRowMapper<>(InpatientDTO.class),
                    "in", wardCode
            );

            log.info("查询在科患者成功，wardCode={}, count={}", wardCode, result.size());
            return result;
        } catch (Exception e) {
            log.error("查询在科患者失败，wardCode={}", wardCode, e);
            return Collections.emptyList();
        }
    }

    /**
     * 按住院号查询患者
     *
     * @param patientId 住院号
     * @return 患者信息，不存在则返回null
     */
    public InpatientDTO findByPatientId(String patientId) {
        if (jdbcTemplate == null) {
            log.warn("Kingbase JdbcTemplate未初始化，无法查询患者");
            return null;
        }

        try {
            String sql = "SELECT patient_id, mrn, series, name, ward_code, status, " +
                    "admission_time, admission_ward_time, discharge_time, height, weight " +
                    "FROM " + SCHEMA + ".inpatients " +
                    "WHERE patient_id = ?";

            List<InpatientDTO> result = jdbcTemplate.query(
                    sql,
                    new BeanPropertyRowMapper<>(InpatientDTO.class),
                    patientId
            );

            if (result.isEmpty()) {
                log.info("未找到患者，patientId={}", maskPatientId(patientId));
                return null;
            }

            return result.get(0);
        } catch (Exception e) {
            log.error("查询患者失败，patientId={}", maskPatientId(patientId), e);
            return null;
        }
    }

    /**
     * 获取患者入科时间
     *
     * @param patientId 住院号
     * @return 入科时间，不存在则返回null
     */
    public java.time.LocalDateTime getAdmissionWardTime(String patientId) {
        InpatientDTO inpatient = findByPatientId(patientId);
        return inpatient != null ? inpatient.getAdmissionWardTime() : null;
    }

    /**
     * 脱敏住院号
     *
     * @param patientId 住院号
     * @return 脱敏后的住院号
     */
    private String maskPatientId(String patientId) {
        if (patientId == null || patientId.length() <= 4) {
            return "****";
        }
        return patientId.substring(0, 2) + "****" + patientId.substring(patientId.length() - 2);
    }
}

package com.digixmed.cloud.icu.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.bson.Document;

/**
 * 血压成对模型
 *
 * 业务目的：显式处理血压的收缩压和舒张压成对关系
 * 输入：MongoDB bedside记录
 * 输出：完整的血压数据对
 * 异常策略：不完整血压不推送，记录WARN日志
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BloodPressurePair {

    /**
     * 收缩压值
     */
    private String systolic;

    /**
     * 舒张压值
     */
    private String diastolic;

    /**
     * 收缩压来源记录
     */
    private Document systolicRecord;

    /**
     * 舒张压来源记录
     */
    private Document diastolicRecord;

    /**
     * 检查血压是否完整
     *
     * @return 是否完整
     */
    public boolean isComplete() {
        return systolic != null && !systolic.isEmpty()
                && diastolic != null && !diastolic.isEmpty();
    }

    /**
     * 获取完整的血压值字符串
     * 格式：收缩压/舒张压
     *
     * @return 血压值字符串
     */
    public String getBloodPressureValue() {
        if (!isComplete()) {
            return null;
        }
        return systolic + "/" + diastolic;
    }

    /**
     * 检查收缩压值是否为数字类型
     *
     * @return 是否为数字
     */
    public boolean isNumericSystolic() {
        if (systolic == null || systolic.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(systolic);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 检查舒张压值是否为数字类型
     *
     * @return 是否为数字
     */
    public boolean isNumericDiastolic() {
        if (diastolic == null || diastolic.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(diastolic);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 检查血压值是否有效
     * 数字类型：必须为正数且收缩压>舒张压
     * 字符串类型：非空即有效
     *
     * @return 是否有效
     */
    public boolean isValid() {
        if (!isComplete()) {
            return false;
        }
        if (isNumericSystolic() && isNumericDiastolic()) {
            try {
                double sys = Double.parseDouble(systolic);
                double dia = Double.parseDouble(diastolic);
                return sys > 0 && dia > 0 && sys > dia;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return !systolic.trim().isEmpty() && !diastolic.trim().isEmpty();
    }
}

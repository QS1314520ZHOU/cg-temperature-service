package com.digixmed.cloud.icu.model;

/**
 * 呼吸机状态枚举
 *
 * 业务目的：表示呼吸机的使用状态
 * 输入：MongoDB bedside记录中的param_HuXiMoShi
 * 输出：呼吸机状态
 * 异常策略：无法判断时返回UNKNOWN
 */
public enum VentilatorState {

    /**
     * 正在使用呼吸机
     * 条件：当前标准时间点param_HuXiMoShi的strVal非null、非空白
     */
    USING("使用呼吸机"),

    /**
     * 停止呼吸机
     * 条件：上一个标准时间点或上一个有效状态为使用呼吸机，
     *       当前标准时间点已经没有有效param_HuXiMoShi
     * 注意：同一个停止事件只生成一次
     */
    STOPPED("停止呼吸机"),

    /**
     * 未使用呼吸机
     * 条件：当前没有有效呼吸机模式，且上一个状态也不是使用呼吸机
     */
    NOT_USING(""),

    /**
     * 未知状态
     * 条件：无法判断状态
     */
    UNKNOWN("");

    private final String displayName;

    VentilatorState(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 获取显示名称
     *
     * @return 显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 是否正在使用呼吸机
     *
     * @return 是否使用
     */
    public boolean isUsing() {
        return this == USING;
    }

    /**
     * 是否已停止呼吸机
     *
     * @return 是否停止
     */
    public boolean isStopped() {
        return this == STOPPED;
    }
}

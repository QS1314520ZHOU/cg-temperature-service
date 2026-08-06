package com.digixmed.cloud.icu.pojo.configParam;


public class ConfigItem {
    private String value;
    private String showStr;

    public void setValue(String value) {
        /* 13 */
        this.value = value;
    }

    public void setShowStr(String showStr) {
        this.showStr = showStr;
    }

    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof ConfigItem)) return false;
        ConfigItem other = (ConfigItem) o;
        if (!other.canEqual(this)) return false;
        Object this$value = getValue(), other$value = other.getValue();
        if ((this$value == null) ? (other$value != null) : !this$value.equals(other$value)) return false;
        Object this$showStr = getShowStr(), other$showStr = other.getShowStr();
        return !((this$showStr == null) ? (other$showStr != null) : !this$showStr.equals(other$showStr));
    }

    protected boolean canEqual(Object other) {
        return other instanceof ConfigItem;
    }



    public String toString() {
        return "ConfigItem(value=" + getValue() + ", showStr=" + getShowStr() + ")";
    }

    public ConfigItem(String value, String showStr) {
        /* 14 */
        this.value = value;
        this.showStr = showStr;
    }

    public ConfigItem() {
    }

    /* 18 */
    public String getValue() {
        return this.value;
    }

    public String getShowStr() {
        /* 19 */
        return this.showStr;
    }
}

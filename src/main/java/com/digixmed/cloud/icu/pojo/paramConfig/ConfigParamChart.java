package com.digixmed.cloud.icu.pojo.paramConfig;


import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

public class ConfigParamChart {
    String rangeMax;
    String rangeMin;

    /*  8 */
    public void setRangeMax(String rangeMax) {
        this.rangeMax = rangeMax;
    }

    String color;
    String render;
    String shape;

    public void setRangeMin(String rangeMin) {
        this.rangeMin = rangeMin;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public void setRender(String render) {
        this.render = render;
    }

    public void setShape(String shape) {
        this.shape = shape;
    }

    /* 10 */
    public String getRangeMax() {
        return this.rangeMax;
    }

    /* 11 */
    public String getRangeMin() {
        return this.rangeMin;
    }

    /* 12 */
    public String getColor() {
        return this.color;
    }

    /* 13 */
    public String getRender() {
        return this.render;
    }

    public String getShape() {
        /* 14 */
        return this.shape;
    }

    public String toString() {
        /* 18 */
        return ToStringBuilder.reflectionToString(this);
    }


    public int hashCode() {
        /* 23 */
        return HashCodeBuilder.reflectionHashCode(this);
    }


    public boolean equals(Object other) {
        /* 28 */
        return EqualsBuilder.reflectionEquals(this, other);
    }
}

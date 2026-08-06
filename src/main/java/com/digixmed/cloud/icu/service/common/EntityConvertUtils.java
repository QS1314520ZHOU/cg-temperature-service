package com.digixmed.cloud.icu.service.common;

import cn.hutool.core.bean.BeanUtil;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;


@Component
public class EntityConvertUtils {
    public static <T> List<T> copyList(List<?> resourceList, Class<T> classObj) {
        /* 21 */
        List<T> targetList = new ArrayList<>();
        /* 22 */
        if (null != resourceList && resourceList.size() > 0) {
            /* 23 */
            resourceList.forEach(sourceObject -> {
                try {
                    T data = classObj.getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
                    BeanUtil.copyProperties(sourceObject, data, true);
                    targetList.add(data);
                    /* 28 */
                } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | java.lang.reflect.InvocationTargetException e) {
                    e.printStackTrace();
                }
            });
            /* 32 */
            return targetList;
        }
        /* 34 */
        return targetList;
    }


    public static <T> T copyTargetObject(Object resourceObject, Class<T> classObj) {
        /* 44 */
        if (null != resourceObject) {
            try {
                /* 46 */
                T data = classObj.getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
                /* 47 */
                BeanUtil.copyProperties(resourceObject, data);
                /* 48 */
                return data;
                /* 49 */
            } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | java.lang.reflect.InvocationTargetException e) {
                /* 50 */
                e.printStackTrace();
            }
        }
        /* 53 */
        return null;
    }
}


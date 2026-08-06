package com.digixmed.cloud.icu.util;

import cn.hutool.core.util.StrUtil;
import com.digixmed.cloud.icu.pojo.commonParam.Item;
import com.digixmed.cloud.icu.pojo.paramConfig.ConfigParamDto;
import com.digixmed.cloud.icu.pojo.tubeExe.TubeExeDto;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;


@Component
public class TubeUtil {
    public static final String PARAM_TUBE_ = "param_tube_";

    public Item getItemByTube(TubeExeDto tubeExe, Map<String, String> code2ParamNameMap) {
        /*  29 */
        if (null == tubeExe || tubeExe.getName() == null) return null;
        /*  30 */
        String paramCode = "param_tube_" + tubeExe.getName();
        /*  31 */
        String name = code2ParamNameMap.get(paramCode);
        /*  32 */
        name = (name == null) ? tubeExe.getName() : name;
        /*  33 */
        return new Item(paramCode, name, "ml", "out", Integer.valueOf(1), "str", name);
    }


    public ConfigParamDto getConfigParamDto(String paramName, String inOutFlag, String dataType, String code) {
        /*  45 */
        ConfigParamDto configParam = new ConfigParamDto();
        /*  46 */
        paramName = dealTubeCodeName(paramName);
        /*  47 */
        configParam.setName(paramName);
        /*  48 */
        configParam.setEnName(paramName);
        /*  49 */
        configParam.setCode(code);
        /*  50 */
        configParam.setCreateTime(new Date());
        /*  51 */
        configParam.setDataType(dataType);
        /*  52 */
        configParam.setFloatCount(Integer.valueOf(1));
        /*  53 */
        configParam.setSrc("patient");
        /*  54 */
        configParam.setUnitCode("unit_ml");
        /*  55 */
        configParam.setCalculation(inOutFlag);
        /*  56 */
        return configParam;
    }


    private String dealTubeCodeName(String paramName) {
        /*  66 */
        if (StrUtil.isNotBlank(paramName)) {
            /*  67 */
            String substring = paramName.substring(paramName.length() - 1);
            /*  68 */
            if ("管".equals(substring)) {
                /*  69 */
                paramName = paramName.substring(0, paramName.length() - 1) + "液";
            }
        }
        /*  72 */
        return paramName;
    }


    private static boolean isYlg(TubeExeDto tubeExe) {
        /*  81 */
        return (null != tubeExe.getType() && tubeExe.getType().contains("引流管"));
    }


    public List<TubeExeDto> getInDayYlgList(Date startTime, Date endTime, List<TubeExeDto> tubeExes) {
        /*  93 */
        List<TubeExeDto> inZoneYlgList = new ArrayList<>();
        /*  94 */
        if (startTime == null || endTime == null) return inZoneYlgList;
        /*  95 */
        for (TubeExeDto tubeExe : tubeExes) {
            /*  96 */
            if (!isYlg(tubeExe) || (
                    /*  97 */         null != tubeExe.getEndTime() && tubeExe.getEndTime().before(startTime))) {
                continue;
            }
            /* 100 */
            if (null != tubeExe.getStartTime() && tubeExe.getStartTime().after(endTime))
                continue;
            /* 102 */
            inZoneYlgList.add(tubeExe);
        }

        /* 105 */
        return inZoneYlgList;
    }
}

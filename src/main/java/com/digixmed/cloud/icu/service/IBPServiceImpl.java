package com.digixmed.cloud.icu.service;

import cn.hutool.core.util.ObjectUtil;
import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.service.common.BaseService;
import com.digixmed.cloud.icu.service.common.HandleService;

import java.util.Date;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IBPServiceImpl
        extends HandleService
        implements BaseService {
    /* 18 */   private static final Logger log = LoggerFactory.getLogger(IBPServiceImpl.class);


    public IntermediateTable special(IntermediateTable table, String pid, Document bedside) {
        /* 23 */
        if (ObjectUtil.isEmpty(table)) {
            /* 24 */
            return null;
        }

        /* 27 */
        String ibpdCode = table.getSignCode();

        /* 29 */
        Date timePoint = table.getTimePoint();
        /* 30 */
        if (ObjectUtil.isEmpty(timePoint)) {
            /* 31 */
            log.info("病人ID：" + table.getPid() + "体征编码：" + ibpdCode + "的记录时间点为空");
            /* 32 */
            return null;
        }
        /* 34 */
        String ibpsCode = "param_ibp_d".equals(ibpdCode) ? "param_ibp_s" : "param_nibp_s";
        /* 35 */
        Document ibps = queryIBPS(pid, timePoint, ibpsCode);
        /* 36 */
        if (ObjectUtil.isEmpty(ibps)) {
            /* 37 */
            return null;
        }
        /* 39 */
        String ibpdStrVal = table.getSignValue();
        /* 40 */
        String ibpsStrVal = (String) getValueFromDocByKey(ibps, "strVal", String.class);
        /* 41 */
        if (ibpsStrVal != null && ibpsStrVal != null) {
            /* 42 */
            String strVal = ibpsStrVal + "/" + ibpsStrVal;
            /* 43 */
            table.setSignValue(strVal);


        } else {



            /* 51 */
            table = null;
        }
        /* 53 */
        return table;
    }
}

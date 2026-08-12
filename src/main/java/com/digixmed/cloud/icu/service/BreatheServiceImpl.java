package com.digixmed.cloud.icu.service;

import cn.hutool.core.util.ObjectUtil;
import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.service.common.BaseService;
import com.digixmed.cloud.icu.service.common.HandleService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


@Service
public class BreatheServiceImpl
        extends HandleService
        implements BaseService {
    /* 18 */   private static final Logger log = LoggerFactory.getLogger(BreatheServiceImpl.class);


    public IntermediateTable special(IntermediateTable table, String pid, Document bedside) {
        /* 23 */
        if (table == null) return null;

        /* 25 */
        Document huXiMoShi = queryHuXiPinLv(pid, table.getTimePoint(), "param_HuXiMoShi");
        /* 26 */
        if (ObjectUtil.isNotEmpty(huXiMoShi)) {
            /* 27 */
            table.setInHuXiJi(Integer.valueOf(1));
        } else {

            /* 30 */
            table.setInHuXiJi(Integer.valueOf(0));
        }
        /* 32 */
        return table;
    }
}

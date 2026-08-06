package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.service.common.BaseService;
import com.digixmed.cloud.icu.service.common.HandleService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


@Service
public class TemperatureServiceImpl
        extends HandleService
        implements BaseService {
    /* 19 */   private static final Logger log = LoggerFactory.getLogger(TemperatureServiceImpl.class);


    public IntermediateTable special(IntermediateTable table, String pid, Document bedside) {
        /* 38 */
        return table;
    }
}


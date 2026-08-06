 package com.digixmed.cloud.icu.service;
 
 import com.digixmed.cloud.icu.pojo.IntermediateTable;
 import com.digixmed.cloud.icu.service.common.BaseService;
 import com.digixmed.cloud.icu.service.common.HandleService;
 import org.bson.Document;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.springframework.stereotype.Service;
 
 @Service
 public class DefaultServiceImpl
   extends HandleService implements BaseService {
   private static final Logger log = LoggerFactory.getLogger(DefaultServiceImpl.class);

   public IntermediateTable special(IntermediateTable table, String pid, Document bedside) {
    return table;
   }
 }

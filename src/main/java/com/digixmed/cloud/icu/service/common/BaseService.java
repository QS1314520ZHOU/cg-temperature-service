package com.digixmed.cloud.icu.service.common;

import com.digixmed.cloud.icu.pojo.IntermediateTable;
import org.bson.Document;

public interface BaseService {
  IntermediateTable handle(Document paramDocument);
  
  IntermediateTable handle4First(Document paramDocument);
}



 package com.digixmed.cloud.icu.pojo.paramConfig;
 import java.lang.annotation.*;

 @Retention(RetentionPolicy.RUNTIME)
 @Documented
 @Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.METHOD, ElementType.PACKAGE, ElementType.PARAMETER, ElementType.TYPE})
 public @interface Access {
   AccessType value() default AccessType._default;
   
   public enum AccessType {
/* 14 */     _default;
   }
 }
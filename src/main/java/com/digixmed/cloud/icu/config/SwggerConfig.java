 package com.digixmed.cloud.icu.config;
 
 import io.swagger.annotations.ApiOperation;
 import org.springframework.context.annotation.Bean;
 import org.springframework.context.annotation.Configuration;
 import springfox.documentation.builders.ApiInfoBuilder;
 import springfox.documentation.builders.PathSelectors;
 import springfox.documentation.builders.RequestHandlerSelectors;
 import springfox.documentation.oas.annotations.EnableOpenApi;
 import springfox.documentation.service.ApiInfo;
 import springfox.documentation.service.Contact;
 import springfox.documentation.spi.DocumentationType;
 import springfox.documentation.spring.web.plugins.Docket;
 

 @EnableOpenApi
 @Configuration
 public class SwggerConfig
 {
   @Bean
   public Docket createRestApi() {
     return (new Docket(DocumentationType.OAS_30))
       .apiInfo(apiInfo())
       .select()
       .apis(RequestHandlerSelectors.withMethodAnnotation(ApiOperation.class))
       .paths(PathSelectors.any())
       .build();
   }
   
   private ApiInfo apiInfo() {
    return (new ApiInfoBuilder())
      .title("深医信息接口服务")
       .description("该服务作用于体温单接口。\n详细问题，请联系开发者")
      .contact(new Contact("975", "无", "无"))
      .version("1.0")
            .build();
   }
 }

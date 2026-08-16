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

   @Bean
   public Docket vitalSignApi() {
     return new Docket(DocumentationType.OAS_30)
       .apiInfo(new ApiInfoBuilder()
         .title("体征回传手动测试接口")
         .description("自动回传关闭时，通过本接口按患者+日期+时间点精准触发回传")
         .version("1.0")
         .build())
       .select()
       .apis(RequestHandlerSelectors.basePackage("com.digixmed.cloud.icu.controller"))
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

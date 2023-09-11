package com.rm.process.rest.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.*;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.contexts.SecurityContext;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableSwagger2
public class SwaggerConfiguration {
    private String version;
    private String title;

    /**
     * API 버전 관리, Docket 객체를 빈으로 정의함으로써 화면 상단에 Spec 분기가 정의됨
     */

    @Bean
    public Docket apiV1() {
        version = "V1";
        title = "victolee API " + version;
        return new Docket(DocumentationType.SWAGGER_2)
                .useDefaultResponseMessages(false)
                .groupName(version)
                .securityContexts(Arrays.asList(securityContext()))
                .securitySchemes(Arrays.asList(apiKey()))
                .select()
                .apis(RequestHandlerSelectors.any())
//                .apis(RequestHandlerSelectors.basePackage("com.victolee.swaggerexam.api.v1"))
                .paths(PathSelectors.ant("/**/v1/api/**"))

                .build()
                .apiInfo(apiInfo(title, version));
    }
    /**
     * API 페이지 하단에 표기되는 README와 같은 형태의 TEXT 영역 설정
     */
    private ApiInfo apiInfo(String title, String version) {
        return new ApiInfo(
                title,
                "Swagger로 생성한 API Docs",
                version,
                "www.example.com",
                new Contact("Contact Me", "www.example.com", "foo@example.com"),
                "Licenses",

                "www.example.com",

                new ArrayList<>());
    }

    private ApiKey apiKey() {
        return new ApiKey("Authorization Header", "Authorization", "header");
    }

    private SecurityContext securityContext() {
        return SecurityContext.builder().securityReferences(globalAuth()).build();
    }

    private List<SecurityReference> globalAuth() {
        return Arrays.asList(new SecurityReference("Authorization Header", new AuthorizationScope[] {
                new AuthorizationScope("global", "access everything")
        }));
    }
}

package com.msvieira.aviation.config;

import com.msvieira.aviation.adapter.web.ProblemDocumentation;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Aviation API Wrapper")
                        .version("1.0.0")
                        .description("Airport lookup by ICAO code. Wraps aviationweather.gov with retries, "
                                + "a circuit breaker and a short-lived cache. Errors use RFC 9457 Problem Details.")
                        .license(new License().name("MIT")))
                .components(
                        new Components().addSchemas(ProblemDocumentation.SCHEMA_NAME, ProblemDocumentation.schema()));
    }
}

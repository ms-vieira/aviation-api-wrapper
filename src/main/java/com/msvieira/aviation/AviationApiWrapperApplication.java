package com.msvieira.aviation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AviationApiWrapperApplication {

    public static void main(String[] args) {
        SpringApplication.run(AviationApiWrapperApplication.class, args);
    }
}

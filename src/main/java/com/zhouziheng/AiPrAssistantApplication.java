package com.zhouziheng;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiPrAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiPrAssistantApplication.class, args);
    }
}

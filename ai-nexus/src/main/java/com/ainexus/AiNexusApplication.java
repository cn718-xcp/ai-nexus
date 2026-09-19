package com.ainexus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Nexus 启动类。
 */
@EnableScheduling
@SpringBootApplication
public class AiNexusApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiNexusApplication.class, args);
    }
}

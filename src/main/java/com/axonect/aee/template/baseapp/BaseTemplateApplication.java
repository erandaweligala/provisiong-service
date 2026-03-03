package com.axonect.aee.template.baseapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;

//@Import(com.adl.et.telco.dte.prom_spring_metrics_plugin.domain.metrics.autoconfigure.PromMetricsAutoConfiguration.class)
@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.axonect.aee.template.baseapp.application.repository")
@EntityScan(basePackages = "com.axonect.aee.template.baseapp.domain.entities.dto")
public class BaseTemplateApplication {
    public static void main(String[] args) {
        SpringApplication.run(BaseTemplateApplication.class, args);
    }
}  
package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.application.filter.XssStringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public SimpleModule xssModule() {
        SimpleModule module = new SimpleModule("XssProtectionModule");
        // Every String field in every @RequestBody gets sanitized automatically
        module.addDeserializer(String.class, new XssStringDeserializer());
        return module;
    }
}

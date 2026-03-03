package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.domain.algorithm.AESEncryptionPlugin;
import com.axonect.aee.template.baseapp.domain.algorithm.EncryptionPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EncryptionConfig {

    @Bean
    public EncryptionPlugin encryptionPlugin() {
        return new AESEncryptionPlugin();
    }
}
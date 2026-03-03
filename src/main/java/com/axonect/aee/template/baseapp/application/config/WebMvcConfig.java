package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.domain.mappers.ApiLoggingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiLoggingInterceptor apiLoggingInterceptor;

    public WebMvcConfig(ApiLoggingInterceptor apiLoggingInterceptor) {
        this.apiLoggingInterceptor = apiLoggingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // Register globally — annotation filters inside interceptor
        registry.addInterceptor(apiLoggingInterceptor)
                .addPathPatterns("/api/user/**")
                .addPathPatterns("/api/services/**"); // optional, can remove
    }
}


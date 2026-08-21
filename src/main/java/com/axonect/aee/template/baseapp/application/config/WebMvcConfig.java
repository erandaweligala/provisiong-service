package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.application.filter.XssHandlerInterceptor;
import com.axonect.aee.template.baseapp.application.monitoring.ApiLatencyThresholdInterceptor;
import com.axonect.aee.template.baseapp.domain.mappers.ApiLoggingInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiLoggingInterceptor apiLoggingInterceptor;
    private final XssHandlerInterceptor xssHandlerInterceptor;
    private final ObjectProvider<ApiLatencyThresholdInterceptor> apiLatencyThresholdInterceptor;

    public WebMvcConfig(ApiLoggingInterceptor apiLoggingInterceptor,
                        XssHandlerInterceptor xssHandlerInterceptor,
                        ObjectProvider<ApiLatencyThresholdInterceptor> apiLatencyThresholdInterceptor) {
        this.apiLoggingInterceptor = apiLoggingInterceptor;
        this.xssHandlerInterceptor = xssHandlerInterceptor;
        this.apiLatencyThresholdInterceptor = apiLatencyThresholdInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        registry.addInterceptor(apiLoggingInterceptor)
                .addPathPatterns("/api/user/**")
                .addPathPatterns("/api/services/**");

        registry.addInterceptor(xssHandlerInterceptor)
                .addPathPatterns("/**");

        // Absent when monitoring.api.enabled is false, hence the ObjectProvider.
        apiLatencyThresholdInterceptor.ifAvailable(interceptor ->
                registry.addInterceptor(interceptor).addPathPatterns("/api/**"));
    }
}


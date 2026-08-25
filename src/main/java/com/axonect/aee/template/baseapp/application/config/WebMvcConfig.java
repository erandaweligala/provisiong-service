package com.axonect.aee.template.baseapp.application.config;

import com.axonect.aee.template.baseapp.application.filter.XssHandlerInterceptor;
import com.axonect.aee.template.baseapp.domain.mappers.ApiLoggingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiLoggingInterceptor apiLoggingInterceptor;
    private final XssHandlerInterceptor xssHandlerInterceptor;

    public WebMvcConfig(ApiLoggingInterceptor apiLoggingInterceptor,
                        XssHandlerInterceptor xssHandlerInterceptor) {
        this.apiLoggingInterceptor = apiLoggingInterceptor;
        this.xssHandlerInterceptor = xssHandlerInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        registry.addInterceptor(apiLoggingInterceptor)
                .addPathPatterns("/api/user/**")
                .addPathPatterns("/api/services/**");

        registry.addInterceptor(xssHandlerInterceptor)
                .addPathPatterns("/**");
    }
}


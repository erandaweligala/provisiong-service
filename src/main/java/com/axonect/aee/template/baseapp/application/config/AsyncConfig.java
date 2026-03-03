package com.axonect.aee.template.baseapp.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * Executor for running parallel validations in createUser.
     *
     * - Core pool size: 50 (increased from 10)
     * - Max pool size: 200 (increased from 20)
     * - Queue capacity: 10000 (increased from 50)
     * - Thread name prefix: validation-exec-
     */
    @Bean(name = "validationExecutor")
    public Executor validationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Minimum number of threads to keep alive
        executor.setCorePoolSize(50);           // Increased from 10

        // Maximum allowed threads
        executor.setMaxPoolSize(200);           // Increased from 20

        // Queue capacity for tasks waiting to be executed
        executor.setQueueCapacity(10000);       // Increased from 50

        // Prefix for thread names (helps in logging)
        executor.setThreadNamePrefix("validation-exec-");

        // Wait for tasks to complete on shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // Initialize the executor
        executor.initialize();

        return executor;
    }
}
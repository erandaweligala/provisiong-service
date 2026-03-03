package com.axonect.aee.template.baseapp.application.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.Executors;

@Configuration
public class AsyncConfiguration {

    @Bean(name = "asyncExecutor")
    public TaskExecutor transactionalExecutor(@Value("${thread.count.dbapiexecutor}") int threads) {

        ConcurrentTaskExecutor executor = new ConcurrentTaskExecutor(Executors.newFixedThreadPool(threads));
        // Decorate with request attributes for HttpServletRequest header access.
        executor.setTaskDecorator(runnable -> {
            RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();

            return () -> {
                RequestContextHolder.setRequestAttributes(requestAttributes);

                runnable.run();
            };
        });

        return executor;
    }

}

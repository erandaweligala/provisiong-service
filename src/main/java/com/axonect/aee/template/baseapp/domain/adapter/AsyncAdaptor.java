package com.axonect.aee.template.baseapp.domain.adapter;

import com.axonect.aee.template.baseapp.domain.exception.AAAException;
import lombok.SneakyThrows;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Component
public class AsyncAdaptor implements AsyncAdaptorInterface {

    private final TaskExecutor executor;

    public AsyncAdaptor(TaskExecutor executor) {
        this.executor = executor;
    }

    /**
     * Execute provided tasks in parallel using supplyAsync. Http Request context will be propagated.
     * Complete when all tasks are completed.
     *
     * @param tasks Tasks to run.
     */

    @Override
    @SneakyThrows
    public CompletableFuture<Object>[] supplyAll(Long timeOut, Supplier<?>... tasks) {
        CompletableFuture<Object>[] completableFutures = Arrays.stream(tasks)
                .map(task -> CompletableFuture.supplyAsync(task, executor))
                .toList() // returns an unmodifiable List
                .toArray(new CompletableFuture[0]);


        try {
            CompletableFuture.allOf(completableFutures).get(Objects.nonNull(timeOut) ? timeOut : 10000L, TimeUnit.MILLISECONDS);
            return completableFutures;
        } catch (ExecutionException e) {

            throw e.getCause();
        } catch (CancellationException | TimeoutException ex) {

            throw new AAAException(
                    "ASYNC_TIMEOUT",
                    "Asynchronous tasks did not complete within the timeout period.",
                    HttpStatus.REQUEST_TIMEOUT
            );
        }
    }

}

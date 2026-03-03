package com.axonect.aee.template.baseapp.domain.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface AsyncAdaptorInterface {
    CompletableFuture<Object>[] supplyAll(Long timeOut, Supplier<?>... tasks);
}

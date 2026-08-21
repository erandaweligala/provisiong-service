package com.axonect.aee.template.baseapp.domain.exception;

import org.springframework.stereotype.Component;

@Component
public class StackTraceTracker {
    public static String displayStackStraceArray(StackTraceElement[] stackTraceElements) {
        StringBuilder stringBuilder = new StringBuilder();
        if (stackTraceElements != null) {
            for (StackTraceElement elem : stackTraceElements) {
                if (elem.getClassName().startsWith("com.axonect.aee.template.baseapp") && elem.getLineNumber() > 0) {
                    stringBuilder.append(elem.toString());
                    break;
                }
            }
        }
        return stringBuilder.toString();
    }
}

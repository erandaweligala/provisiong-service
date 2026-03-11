package com.axonect.aee.template.baseapp.domain.mappers;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.ActionLogRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import com.axonect.aee.template.baseapp.domain.util.LoggableAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Component
@Slf4j
public class ApiLoggingInterceptor implements HandlerInterceptor {

    private final ActionLogRepository actionLogRepository;
    private final ObjectMapper objectMapper;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final EventMapper eventMapper;
    private final Executor asyncExecutor;

    private static final String CREATE = "CREATE";
    private static final Pattern UNDERSCORE_PATTERN = Pattern.compile("_");
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("(?<!^)([A-Z])");

    public ApiLoggingInterceptor(ActionLogRepository actionLogRepository,
                                  ObjectMapper objectMapper,
                                  KafkaEventPublisher kafkaEventPublisher,
                                  EventMapper eventMapper,
                                  @Qualifier("validationExecutor") Executor asyncExecutor) {
        this.actionLogRepository = actionLogRepository;
        this.objectMapper = objectMapper;
        this.kafkaEventPublisher = kafkaEventPublisher;
        this.eventMapper = eventMapper;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {

        try {

            if (!(handler instanceof HandlerMethod handlerMethod)) {
                return;
            }

            if (!handlerMethod.hasMethodAnnotation(LoggableAction.class)) {
                return;
            }

            ActionLog logEntity = new ActionLog();
            logEntity.setAction(toUserFriendlyText(handlerMethod.getMethod().getName()));
            logEntity.setDateTime(LocalDateTime.now());
            logEntity.setHttpStatus(String.valueOf(response.getStatus()));

            // Request parsing
            JsonNode requestJson = readRequestJson(request);

            String requestId = extractValue(Constants.REQUEST_ID, requestJson, request);
            String username  = extractValue(Constants.USERNAME, requestJson, request);
            String groupId   = extractValue(Constants.GROUP_ID, requestJson, request);

            logEntity.setRequestId(requestId);
            logEntity.setUserName(username);
            logEntity.setGroupId(groupId);

            // Response parsing
            JsonNode responseJson = readResponseJson(response);

            String description = null;
            String resultCode = null;

            if (responseJson != null) {
                if (responseJson.has("message")) {
                    description = responseJson.get("message").asText();
                }
                if (responseJson.has("error_code")) {
                    resultCode = responseJson.get("error_code").asText();
                }
            }

            logEntity.setDescription(description);
            logEntity.setResultCode(resultCode);

            // Publish to Kafka asynchronously to avoid blocking the response thread
            asyncExecutor.execute(() -> publishActionLogEvents(logEntity));

        } catch (Exception loggingEx) {
            log.error("Failed to log API action. This will not affect API response.", loggingEx);
        }
    }

    private void publishActionLogEvents(ActionLog actionLog) {
        try {
            DBWriteRequestGeneric dbEvent = eventMapper.toActionLogDBWriteEvent(CREATE, actionLog);
            PublishResult dbResult = kafkaEventPublisher.publishActionLogDBWriteEvent(dbEvent);

            if (dbResult.isCompleteFailure()) {
                log.warn("Failed to publish action log events for action '{}', requestId '{}'",
                        actionLog.getAction(), actionLog.getRequestId());
            }

        } catch (Exception e) {
            log.error("Exception while publishing action log events for action '{}': {}",
                    actionLog.getAction(), e.getMessage(), e);
        }
    }

    // =====================================================
    // Helpers
    // =====================================================

    private JsonNode readRequestJson(HttpServletRequest request) {
        try {
            ContentCachingRequestWrapper cached = extractCachedRequest(request);
            if (cached == null) {
                return null;
            }

            String body = new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
            return parseJson(body);

        } catch (Exception e) {
            log.warn("Unable to read request body", e);
            return null;
        }
    }

    private JsonNode readResponseJson(HttpServletResponse response) {
        try {
            ContentCachingResponseWrapper cached = extractCachedResponse(response);
            if (cached == null) {
                return null;
            }

            String body = new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
            return parseJson(body);

        } catch (Exception e) {
            log.warn("Unable to read response body", e);
            return null;
        }
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.debug("Body is not valid JSON, skipping JSON parsing");
            return null;
        }
    }

    private String extractValue(String key, JsonNode json, HttpServletRequest request) {

        if (json != null && json.has(key)) {
            return json.get(key).asText();
        } else if (json != null && key.equals(Constants.USERNAME) && json.has(Constants.USER_ID)) {
            return json.get(Constants.USER_ID).asText();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, String> pathVars =
                    (Map<String, String>) request.getAttribute(
                            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

            if (pathVars != null && pathVars.containsKey(key)) {
                return pathVars.get(key);
            } else if (key.equals(Constants.USERNAME) && pathVars != null && pathVars.containsKey(Constants.USER_ID)) {
                return pathVars.get(Constants.USER_ID);
            }
        } catch (Exception e) {
            log.debug("Failed to extract path variable {}", key);
        }

        return request.getParameter(key);
    }

    private ContentCachingRequestWrapper extractCachedRequest(HttpServletRequest request) {
        HttpServletRequest current = request;
        while (current instanceof HttpServletRequestWrapper wrapper) {
            if (current instanceof ContentCachingRequestWrapper cached) {
                return cached;
            }
            current = (HttpServletRequest) wrapper.getRequest();
        }
        return null;
    }

    private ContentCachingResponseWrapper extractCachedResponse(HttpServletResponse response) {
        HttpServletResponse current = response;
        while (current instanceof HttpServletResponseWrapper wrapper) {
            if (current instanceof ContentCachingResponseWrapper cached) {
                return cached;
            }
            current = (HttpServletResponse) wrapper.getResponse();
        }
        return null;
    }

    private static String toUserFriendlyText(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        // Use pre-compiled patterns instead of recompiling on every call
        String result = UNDERSCORE_PATTERN.matcher(input).replaceAll(" ");
        result = CAMEL_CASE_PATTERN.matcher(result).replaceAll(" $1");
        result = result.toLowerCase().trim();

        String[] words = result.split("\\s+");
        StringBuilder builder = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                builder.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1))
                        .append(" ");
            }
        }

        return builder.toString().trim();
    }

}

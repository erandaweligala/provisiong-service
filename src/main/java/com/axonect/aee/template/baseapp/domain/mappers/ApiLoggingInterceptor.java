package com.axonect.aee.template.baseapp.domain.mappers;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.ActionLogRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import com.axonect.aee.template.baseapp.domain.events.ActionLogEvent;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiLoggingInterceptor implements HandlerInterceptor {

    private final ActionLogRepository actionLogRepository;
    private final ObjectMapper objectMapper;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final EventMapper eventMapper;

    private static final String CREATE = "CREATE";

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {

        try {

            if (!(handler instanceof HandlerMethod handlerMethod)) {
                log.debug("Handler is not a HandlerMethod, skipping action log");
                return;
            }

            if (!handlerMethod.hasMethodAnnotation(LoggableAction.class)) {
                log.debug("Method {} not annotated with @LoggableAction, skipping log",
                        handlerMethod.getMethod().getName());
                return;
            }

            log.info("Starting API logging for method: {}", handlerMethod.getMethod().getName());

            ActionLog logEntity = new ActionLog();
            logEntity.setAction(toUserFriendlyText(handlerMethod.getMethod().getName()));
            logEntity.setDateTime(LocalDateTime.now());
            logEntity.setHttpStatus(String.valueOf(response.getStatus()));

            // --------------------
            // Request parsing
            // --------------------
            JsonNode requestJson = readRequestJson(request);

            String requestId = extractValue(Constants.REQUEST_ID, requestJson, request);
            String username  = extractValue(Constants.USERNAME, requestJson, request);
            String groupId   = extractValue(Constants.GROUP_ID, requestJson, request);

            log.debug("Extracted requestId={}, username={}, groupId={}",
                    requestId, username, groupId);

            logEntity.setRequestId(requestId);
            logEntity.setUserName(username);
            logEntity.setGroupId(groupId);

            // --------------------
            // Response parsing
            // --------------------
            JsonNode responseJson = readResponseJson(response);

            String description = null;
            String resultCode = null ;

            if (responseJson != null) {
                if (responseJson.has("message")) {
                    description = responseJson.get("message").asText();
                }
                if (responseJson.has("error_code")) {
                    resultCode = responseJson.get("error_code").asText();
                }
            }

            log.debug("Response parsed: resultCode={}, description={}",
                    resultCode, description);

            logEntity.setDescription(description);
            logEntity.setResultCode(resultCode);

            // PUBLISH TO KAFKA INSTEAD OF SAVING TO DB
            publishActionLogEvents(logEntity);

            log.info("API action log events published successfully for action={}", logEntity.getAction());

        } catch (Exception loggingEx) {
            // NEVER break the API because of logging
            log.error("Failed to log API action. This will not affect API response.", loggingEx);
        }
    }

    /**
     * Publish ActionLog events to Kafka
     */
    private void publishActionLogEvents(ActionLog actionLog) {
        try {
            /*// Publish ActionLogEvent
            ActionLogEvent actionLogEvent = eventMapper.toActionLogEvent(actionLog);
            PublishResult eventResult = kafkaEventPublisher.publishActionLogEvent("ACTION_LOG_CREATED", actionLogEvent);*/

            // Publish DBWriteRequestGeneric
            DBWriteRequestGeneric dbEvent = eventMapper.toActionLogDBWriteEvent(CREATE, actionLog);
            PublishResult dbResult = kafkaEventPublisher.publishActionLogDBWriteEvent(dbEvent);

            // Log warnings if publishing failed (but don't throw exception - logging should never break API)
            if (dbResult.isCompleteFailure() || dbResult.isCompleteFailure()) {
                log.warn("Failed to publish action log events for action '{}', requestId '{}'",
                        actionLog.getAction(), actionLog.getRequestId());
            }

            if (!dbResult.isDcSuccess() || !dbResult.isDcSuccess()) {
                log.warn("Partial failure publishing action log events to DC cluster for action '{}'",
                        actionLog.getAction());
            }

        } catch (Exception e) {
            // Log error but don't propagate - logging failures should never break the API
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
                log.debug("Request is not wrapped in ContentCachingRequestWrapper");
                return null;
            }

            String body = new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
            log.debug("Request body length: {}", body.length());

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
                log.debug("Response is not wrapped in ContentCachingResponseWrapper");
                return null;
            }

            String body = new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
            log.debug("Response body length: {}", body.length());

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

        // 1️ Request body
        if (json != null && json.has(key)) {
            return json.get(key).asText();
        }else if (json != null && key.equals(Constants.USERNAME) && json.has(Constants.USER_ID)) {
            return json.get(Constants.USER_ID).asText();
        }
        // 2️ Path variables
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> pathVars =
                    (Map<String, String>) request.getAttribute(
                            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

            if (pathVars != null && pathVars.containsKey(key)) {
                return pathVars.get(key);
            } else if (key.equals(Constants.USERNAME) && pathVars != null && pathVars.containsKey(Constants.USER_ID)){
                return pathVars.get(Constants.USER_ID);
            }
        } catch (Exception e) {
            log.debug("Failed to extract path variable {}", key);
        }

        // 3️ Query parameters
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

        // Replace underscores with spaces
        String result = input.replaceAll("_", " ");

        // Insert space before capital letters (camelCase)
        result = result.replaceAll("(?<!^)([A-Z])", " $1");

        // Normalize spaces and lowercase everything
        result = result.toLowerCase().trim();

        // Capitalize each word
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




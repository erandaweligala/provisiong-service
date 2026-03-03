package com.axonect.aee.template.baseapp.domain.mappers;

import com.axonect.aee.template.baseapp.application.config.KafkaEventPublisher;
import com.axonect.aee.template.baseapp.application.repository.ActionLogRepository;
import com.axonect.aee.template.baseapp.domain.entities.dto.ActionLog;
import com.axonect.aee.template.baseapp.domain.events.DBWriteRequestGeneric;
import com.axonect.aee.template.baseapp.domain.events.EventMapper;
import com.axonect.aee.template.baseapp.domain.events.PublishResult;
import com.axonect.aee.template.baseapp.domain.util.Constants;
import com.axonect.aee.template.baseapp.domain.util.LoggableAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class ApiLoggingInterceptorTest {

    @Mock
    private ActionLogRepository actionLogRepository;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private EventMapper eventMapper;

    @Mock
    private PublishResult publishResult;

    @InjectMocks
    private ApiLoggingInterceptor interceptor;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        interceptor = new ApiLoggingInterceptor(
                actionLogRepository,
                objectMapper,
                kafkaEventPublisher,
                eventMapper
        );
    }

    // ---------------------------------------------------
    // Test Controller
    // ---------------------------------------------------

    static class TestController {

        @LoggableAction
        public void createUser() {}

        public void nonLoggableMethod() {}

        @LoggableAction
        public void update_user() {}
    }

    // ---------------------------------------------------
    // TESTS
    // ---------------------------------------------------

    @Test
    void afterCompletion_HandlerNotHandlerMethod_ShouldSkip() {
        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new Object(),
                null
        );

        verifyNoInteractions(kafkaEventPublisher);
    }

    @Test
    void afterCompletion_MethodWithoutAnnotation_ShouldSkip() throws Exception {
        Method method = TestController.class.getMethod("nonLoggableMethod");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                handlerMethod,
                null
        );

        verifyNoInteractions(kafkaEventPublisher);
    }



    @Test
    void afterCompletion_CompleteFailure_ShouldStillNotThrow() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenReturn(publishResult);

        when(publishResult.isCompleteFailure()).thenReturn(true);
        when(publishResult.isDcSuccess()).thenReturn(false);

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                handlerMethod,
                null
        );

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void afterCompletion_PublishThrowsException_ShouldNotBreak() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenThrow(new RuntimeException("Kafka down"));

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                handlerMethod,
                null
        );

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void extract_FromPathVariables() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenReturn(publishResult);

        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isDcSuccess()).thenReturn(true);

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("requestId", "100")
        );

        interceptor.afterCompletion(request,
                new MockHttpServletResponse(),
                handlerMethod,
                null);

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void extract_FromQueryParam() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenReturn(publishResult);

        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isDcSuccess()).thenReturn(true);

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("requestId", "200");

        interceptor.afterCompletion(request,
                new MockHttpServletResponse(),
                handlerMethod,
                null);

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void extract_UsernameFallbackToUserId() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenReturn(publishResult);

        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isDcSuccess()).thenReturn(true);

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("""
                {"user_id":"U1"}
                """.getBytes());

        ContentCachingRequestWrapper wrapper =
                new ContentCachingRequestWrapper(request);

        interceptor.afterCompletion(wrapper,
                new MockHttpServletResponse(),
                handlerMethod,
                null);

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void noWrappers_ShouldSkipBodyReading() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenReturn(publishResult);

        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isDcSuccess()).thenReturn(true);

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                handlerMethod,
                null
        );

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void afterCompletion_WithExceptionParam_ShouldStillLog() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenReturn(publishResult);

        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isDcSuccess()).thenReturn(true);

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                handlerMethod,
                new RuntimeException("Controller error")
        );

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void extract_FromRequestBody_DirectKey() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenReturn(publishResult);

        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isDcSuccess()).thenReturn(true);

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("""
            {"request_id":"REQ-123","username":"john_doe","group_id":"GRP-1"}
            """.getBytes());

        ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(request);
        // Force body to be cached
        wrapper.getInputStream().readAllBytes();

        interceptor.afterCompletion(wrapper, new MockHttpServletResponse(), handlerMethod, null);

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void extract_ResponseWithMessageAndErrorCode() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenReturn(publishResult);

        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isDcSuccess()).thenReturn(true);

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(mockResponse);
        responseWrapper.getWriter().write("""
            {"message":"Success","error_code":"0000"}
            """);
        responseWrapper.getWriter().flush();

        interceptor.afterCompletion(new MockHttpServletRequest(), responseWrapper, handlerMethod, null);

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void extract_UsernameFromPathVariable_FallbackToUserId() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenReturn(mock(DBWriteRequestGeneric.class));

        when(kafkaEventPublisher.publishActionLogDBWriteEvent(any()))
                .thenReturn(publishResult);

        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isDcSuccess()).thenReturn(true);

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        MockHttpServletRequest request = new MockHttpServletRequest();
        // Provide user_id in path variables but NOT username
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("user_id", "U42")
        );

        interceptor.afterCompletion(request, new MockHttpServletResponse(), handlerMethod, null);

        verify(kafkaEventPublisher).publishActionLogDBWriteEvent(any());
    }

    @Test
    void afterCompletion_EventMapperThrowsException_ShouldNotBreak() throws Exception {

        when(eventMapper.toActionLogDBWriteEvent(any(), any()))
                .thenThrow(new RuntimeException("Mapping failed"));

        Method method = TestController.class.getMethod("createUser");
        HandlerMethod handlerMethod = new HandlerMethod(new TestController(), method);

        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                handlerMethod,
                null
        );

        // kafkaEventPublisher should never be reached
        verifyNoInteractions(kafkaEventPublisher);
    }

    @Test
    void toUserFriendlyText_CamelCase() throws Exception {
        Method method = ApiLoggingInterceptor.class
                .getDeclaredMethod("toUserFriendlyText", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, "createUserAccount");

        assertEquals("Create User Account", result);
    }
    @Test
    void toUserFriendlyText_WithUnderscore() throws Exception {
        Method method = ApiLoggingInterceptor.class
                .getDeclaredMethod("toUserFriendlyText", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, "update_user");

        assertEquals("Update User", result);
    }


    @Test
    void toUserFriendlyText_Null() throws Exception {
        Method method = ApiLoggingInterceptor.class
                .getDeclaredMethod("toUserFriendlyText", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, (String) null);

        assertNull(result);
    }

    @Test
    void toUserFriendlyText_BlankString() throws Exception {
        Method method = ApiLoggingInterceptor.class
                .getDeclaredMethod("toUserFriendlyText", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, "   ");

        assertEquals("   ", result);
    }

}

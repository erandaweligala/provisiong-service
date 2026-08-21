package com.axonect.aee.template.baseapp.application.constants;

public class LoggingAdviceConstants {

    private LoggingAdviceConstants(){}

    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";
    public static final String USERNAME = "username";
    public static final String PLAN_ID = "planId";
    public static final String REQUEST_INITIATED = "UP_REQUEST_INITIATED|0|REQUEST_METHOD:{}|REQUEST_URI:{}|REQUEST_BODY:{}";
    public static final String REQUEST_TERMINATED = "UP_REQUEST_TERMINATED|{}|REASON:{}";
    public static final String EXCEPTION_STACK_TRACE = "UP_EXCEPTION|{}|ERROR_MESSAGE:{}|STACK_TRACE:{}";
    public static final String SERVICE_TERMINATION = "UP_SERVICE_TERMINATED|{}|MESSAGE:{}";
    public static final String API_CALL = "UP_API_CALL|{}|METHOD:{}|API_URL:{}|HTTP_STATUS:{}";
    public static final String API_CALL_EXCEPTION = "UP_API_CALL_EXCEPTION|{}|METHOD:{}|API_URL:{}|HTTP_STATUS:{}|API_RESPONSE:{}|ERROR_MESSAGE:{}|STACK_TRACE:{}";
    public static final String UP_KAFKA = "UP_KAFKA|{}|TYPE:{}|MESSAGE:{}";
    public static final String UP_DB = "UP_DB|{}|OPERATION:{}|RESPONSE_SIZE:{}";

}

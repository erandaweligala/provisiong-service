/**
 * Copyrights 2023 Axiata Digital Labs Pvt Ltd.
 * All Rights Reserved.
 * <p>
 * These material are unpublished, proprietary, confidential source
 * code of Axiata Digital Labs Pvt Ltd (ADL) and constitute a TRADE
 * SECRET of ADL.
 * <p>
 * ADL retains all title to and intellectual property rights in these
 * materials.
 */
package com.axonect.aee.template.baseapp.application.monitoring;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerHttpObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Adds an {@code api} tag to {@code http_server_requests} naming the catalog
 * entry that served the request, and makes sure the {@code status} and
 * {@code outcome} beside it are the ones the caller actually saw.
 *
 * <p>Without the tag the dashboard has to identify endpoints by
 * {@code method}/{@code uri} pairs, which breaks whenever a controller path
 * changes and reads poorly on a panel legend. The tag's cardinality is bounded
 * by the catalog: anything not in it collapses to a single {@code other}
 * series.</p>
 *
 * <p>The two corrections here exist for one reason: a request that failed has to
 * be counted as a failure, against the API it was aimed at. Neither of them
 * leaves a gap on the dashboard when it is missing - both quietly report a
 * success instead, which is the one kind of wrong a failure count must not
 * be.</p>
 */
public class ApiNameObservationConvention extends DefaultServerRequestObservationConvention {

    static final String API_TAG = "api";

    private static final String METHOD_TAG = "method";
    private static final String URI_TAG = "uri";

    /**
     * What Spring puts in the {@code uri} tag when the request never reached a
     * handler and so has no URI template of its own.
     */
    private static final String UNKNOWN_URI = "UNKNOWN";

    /** The answer an unhandled failure is about to be given, once the error dispatch runs. */
    private static final KeyValue SERVER_ERROR_STATUS = KeyValue.of(LowCardinalityKeyNames.STATUS, "500");
    private static final KeyValue SERVER_ERROR_OUTCOME = KeyValue.of(LowCardinalityKeyNames.OUTCOME, "SERVER_ERROR");

    private final ApiEndpointRegistry registry;

    public ApiNameObservationConvention(ApiEndpointRegistry registry) {
        this.registry = registry;
    }

    /**
     * Reads the method and templated URI back off the tags the default
     * convention just produced, rather than re-deriving them from the request.
     * That keeps this convention in step with however Spring chose to template -
     * or redact, for unmapped and redirected requests - the path.
     */
    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        KeyValues keyValues = super.getLowCardinalityKeyValues(context);
        String api = apiFor(context, valueOf(keyValues, METHOD_TAG), valueOf(keyValues, URI_TAG));
        return keyValues.and(KeyValue.of(API_TAG, api));
    }

    /**
     * The status the caller was answered with, rather than the one the response
     * happened to be carrying when the observation stopped.
     *
     * <p>The observation is stopped as the request leaves the filter chain, which
     * is before the container's error dispatch has set a status. An exception
     * nothing handled - one thrown by a filter, or by anything
     * {@code GlobalExceptionHandler} does not cover - is therefore recorded with
     * whatever status the response still had, normally 200, even though the
     * caller received a 500. Left alone that understates the 5xx count by exactly
     * the failures nobody planned for, and overstates the success count by the
     * same amount.</p>
     */
    @Override
    protected KeyValue status(ServerRequestObservationContext context) {
        return unanswered(context) ? SERVER_ERROR_STATUS : super.status(context);
    }

    /**
     * {@code outcome} is Micrometer's summary of {@link #status}, so it has to be
     * corrected with it - the dashboard splits success from failure on this tag,
     * and reads the status only to break the failures down afterwards.
     */
    @Override
    protected KeyValue outcome(ServerRequestObservationContext context) {
        return unanswered(context) ? SERVER_ERROR_OUTCOME : super.outcome(context);
    }

    /**
     * @return true when the request ended in an error that the response does not
     * yet show, which is the state the observation is stopped in.
     */
    private static boolean unanswered(ServerRequestObservationContext context) {
        return context.getError() != null
                && (context.getResponse() == null || context.getResponse().getStatus() < 400);
    }

    /**
     * The catalog entry the request belongs to.
     *
     * <p>The templated URI is the reliable answer and is tried first. A request
     * rejected before Spring matched a handler - a missing {@code channel}
     * header, credentials that did not check out, the rate limiter, the request
     * firewall - has no template, and Spring reports it as {@value #UNKNOWN_URI}.
     * Those are 4xx answered to a caller who was asking for a real API, so
     * leaving them on {@code other} takes them off that API's failure count and
     * out of the service totals, hiding precisely the traffic the dashboard is
     * there to show. Matching the path the caller asked for puts them back;
     * anything the catalog does not recognise is still {@code other}.</p>
     */
    private String apiFor(ServerRequestObservationContext context, String method, String templatedUri) {
        if (!UNKNOWN_URI.equals(templatedUri)) {
            return registry.tagFor(method, templatedUri);
        }
        HttpServletRequest request = context.getCarrier();
        return request == null
                ? ApiEndpointRegistry.UNMATCHED
                : registry.tagForRequestPath(method, request.getRequestURI(), request.getContextPath());
    }

    private static String valueOf(KeyValues keyValues, String key) {
        for (KeyValue keyValue : keyValues) {
            if (key.equals(keyValue.getKey())) {
                return keyValue.getValue();
            }
        }
        return null;
    }
}

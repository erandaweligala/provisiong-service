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
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Adds an {@code api} tag to {@code http_server_requests} naming the catalog
 * entry that served the request.
 *
 * <p>Without it the dashboard has to identify endpoints by
 * {@code method}/{@code uri} pairs, which breaks whenever a controller path
 * changes and reads poorly on a panel legend. The tag's cardinality is bounded
 * by the catalog: anything not in it collapses to a single {@code other}
 * series.</p>
 */
public class ApiNameObservationConvention extends DefaultServerRequestObservationConvention {

    static final String API_TAG = "api";

    /** Low cardinality key holding the templated request path. */
    private static final String URI_TAG = "uri";

    private final ApiEndpointRegistry registry;

    public ApiNameObservationConvention(ApiEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        KeyValues keyValues = super.getLowCardinalityKeyValues(context);
        return keyValues.and(KeyValue.of(API_TAG, registry.tagFor(httpMethod(context), uri(keyValues))));
    }

    private String httpMethod(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        return request == null ? null : request.getMethod();
    }

    /**
     * Reads the templated URI back off the tags the default convention just
     * produced, rather than re-deriving it from the request. That keeps this
     * convention in step with however Spring chose to template - or redact, for
     * unmapped and redirected requests - the path.
     */
    private String uri(KeyValues keyValues) {
        for (KeyValue keyValue : keyValues) {
            if (URI_TAG.equals(keyValue.getKey())) {
                return keyValue.getValue();
            }
        }
        return null;
    }
}

package com.axonect.aee.template.baseapp.application.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
public class RequestCheckFilter implements Filter {

    private static final Set<String> IGNORE_ROUTES = Set.of(
            "/actuator/prometheus"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String endpoint = req.getRequestURI();

        if (endpoint != null && IGNORE_ROUTES.contains(endpoint)) {
            chain.doFilter(request, response);
            return;
        }

        chain.doFilter(request, response);
    }
}

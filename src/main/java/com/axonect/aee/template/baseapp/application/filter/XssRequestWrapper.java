package com.axonect.aee.template.baseapp.application.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class XssRequestWrapper extends HttpServletRequestWrapper {

    private static final Pattern HTML_PATTERN = Pattern.compile("<[^>]+>", Pattern.CASE_INSENSITIVE);

    private final byte[] cachedBody;

    public XssRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        cachedBody = request.getInputStream().readAllBytes();
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values == null) return null;
        for (String value : values) {
            rejectIfHtml(value);
        }
        return values;
    }

    @Override
    public String getParameter(String name) {
        String value = super.getParameter(name);
        rejectIfHtml(value);
        return value;
    }

    @Override
    public String getHeader(String name) {
        String value = super.getHeader(name);
        rejectIfHtml(value);
        return value;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override public int read() { return byteStream.read(); }
            @Override public boolean isFinished() { return byteStream.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {}
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    private void rejectIfHtml(String value) {
        if (value != null && HTML_PATTERN.matcher(value).find()) {
            throw new IllegalArgumentException("Input contains invalid HTML content");
        }
    }
}

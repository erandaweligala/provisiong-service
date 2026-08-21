package com.axonect.aee.template.baseapp.application.filter;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.regex.Pattern;

public class XssStringDeserializer extends StdDeserializer<String> {

    private static final Pattern HTML_PATTERN = Pattern.compile("<[^>]+>", Pattern.CASE_INSENSITIVE);

    public XssStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        String value = p.getValueAsString();
        if (value == null) return null;
        if (HTML_PATTERN.matcher(value).find()) {
            throw new JsonParseException(p, "Input contains invalid HTML content");
        }
        return value.trim();
    }
}

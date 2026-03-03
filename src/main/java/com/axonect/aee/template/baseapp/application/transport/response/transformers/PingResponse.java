package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import lombok.Data;

@Data
public class PingResponse {

    private String status; // SUCCESS or FAILED
}

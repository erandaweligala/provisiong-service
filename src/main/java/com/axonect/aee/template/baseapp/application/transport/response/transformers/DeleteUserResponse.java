package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeleteUserResponse {

    @JsonProperty("request_id")
    private String requestId;

    private String status;

    private String message;
}

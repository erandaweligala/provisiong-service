package com.axonect.aee.template.baseapp.application.transport.request.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * DTO for Delete User API request.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeleteUserRequest {

    @NotBlank(message = "request_id is mandatory")
    @JsonProperty("request_id") // ensure JSON matches
    private String requestId;
}
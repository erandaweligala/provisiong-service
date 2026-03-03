package com.axonect.aee.template.baseapp.application.transport.request.entities;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // this ensures null fields are not serialized
public class ApiResponse {
    private boolean success;
    private String message;
    private Object data;



    // optional convenience constructor
    public ApiResponse(boolean success, Object data) {
        this.success = success;
        this.data = data;
        this.message = null;
    }

}

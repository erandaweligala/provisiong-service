package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * DTO for paginated user list response
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagedUserResponse {

    @JsonProperty("page")
    private Integer page;

    @JsonProperty("page_size")
    private Integer pageSize; // JSON will still be "page_size"

    @JsonProperty("total_records")
    private Long totalRecords; // JSON will still be "total_records"

    @JsonProperty("users")
    private List<UserResponse> users;
}

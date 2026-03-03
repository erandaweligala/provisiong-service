package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AllocatedBandwidth {

    @JsonProperty("downlink")
    private String downlink;

    @JsonProperty("uplink")
    private String uplink;
}

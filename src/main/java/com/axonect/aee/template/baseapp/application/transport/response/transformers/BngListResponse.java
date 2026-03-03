package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BngListResponse {
    private String bngName;
    private String bngIp;
}
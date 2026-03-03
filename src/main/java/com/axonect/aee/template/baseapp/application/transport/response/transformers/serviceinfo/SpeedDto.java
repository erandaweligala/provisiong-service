package com.axonect.aee.template.baseapp.application.transport.response.transformers.serviceinfo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SpeedDto {
    private String uplinkSpeed;
    private String downlinkSpeed;
}

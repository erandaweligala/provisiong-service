package com.axonect.aee.template.baseapp.application.transport.request.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BngFilterRequest {
    private String bngId;
    private String bngName;
    private String bngIp;
    private String status;

    // Pagination parameters
    private Integer page = 1;
    private Integer size = 10;

    // Sorting parameters
    private String sortBy = "createdDate";
    private String sortDirection = "DESC";
}
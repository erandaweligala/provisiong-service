package com.axonect.aee.template.baseapp.application.transport.response.transformers;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageDetails {
    private long totalRecords;
    private int pageNumber;
    private int pageElementCount;
}

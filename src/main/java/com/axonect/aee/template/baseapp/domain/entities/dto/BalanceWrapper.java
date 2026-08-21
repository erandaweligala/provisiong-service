package com.axonect.aee.template.baseapp.domain.entities.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BalanceWrapper {
    private String sessionTimeOut;
    private long concurrency;
    private List<Balance> balance;
}

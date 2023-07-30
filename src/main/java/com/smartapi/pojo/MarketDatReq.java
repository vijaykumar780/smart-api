package com.smartapi.pojo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MarketDatReq {
    private String mode;
    private ExchangeTokens exchangeTokens;
}

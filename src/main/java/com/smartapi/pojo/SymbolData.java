package com.smartapi.pojo;


import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class SymbolData {
    private String token;
    private String symbol;
    private LocalDate expiry;
    private String expiryString;
    private String name; // nifty, finnifty
    private int strike;
    private String lotSize;
}

package com.smartapi.pojo;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class OptionData {
    private String symbol;
    private int oi;
}

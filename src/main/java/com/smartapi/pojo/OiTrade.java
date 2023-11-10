package com.smartapi.pojo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OiTrade {
    int ceOi;
    int peOi;
    boolean eligible;
    int strike;
}

package com.smartapi.pojo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class OrderData {
    LocalDateTime orderDateTime;
    Double averageprice;
}

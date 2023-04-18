package com.smartapi.pojo;

import com.angelbroking.smartapi.models.Order;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlacedOrders {
    Order buyOrder;
    Order targetOrder;
    Order slOrder;

    Double buyPrice;
    Double percentPrice; // sl is kept these many points away after placing buy order
    Double slPrice;
    String symbol;
    String token;
}

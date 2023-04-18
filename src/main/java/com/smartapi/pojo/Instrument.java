package com.smartapi.pojo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Instrument {
    /*
        {"token":"3578","symbol":"696TN51-SG","name":"696TN51","expiry":"",
                "strike":"-1.000000","lotsize":"100","instrumenttype":"","exch_seg":"NSE",
                "tick_size":"1.000000"}
        */
    String token;
    String symbol;
    String name;
    String expiry;
    String strike;
    String lotsize;

    String instrumenttype;
    String exch_seg;
    String tick_size;

    @Override
    public String toString() {
        return "Instrument{" +
                "token='" + token + '\'' +
                ", symbol='" + symbol + '\'' +
                ", name='" + name + '\'' +
                ", expiry='" + expiry + '\'' +
                ", strike='" + strike + '\'' +
                ", lotsize='" + lotsize + '\'' +
                ", instrumenttype='" + instrumenttype + '\'' +
                ", exch_seg='" + exch_seg + '\'' +
                ", tick_size='" + tick_size + '\'' +
                '}';
    }
}

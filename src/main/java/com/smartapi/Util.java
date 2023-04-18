package com.smartapi;

import okhttp3.Request;
import okhttp3.Request.Builder;

public class Util {
    public static Request addHeaders(Builder builder, String privateKey, String hostIp) {
        return builder.addHeader("Accept", "application/json").addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB").addHeader("X-ClientLocalIP", hostIp)
                .addHeader("X-ClientPublicIP", hostIp).addHeader("X-MACAddress", hostIp)
                .addHeader("X-PrivateKey", privateKey).build();
    }

    public static int getMonth(String date) {
        if(date.contains("JAN"))
            return 1;
        if(date.contains("FEB"))
            return 2;
        if(date.contains("MAR"))
            return 3;
        if(date.contains("APR"))
            return 4;
        if(date.contains("MAY"))
            return 5;
        if(date.contains("JUN"))
            return 6;
        if(date.contains("JUL"))
            return 7;
        if(date.contains("AUG"))
            return 8;
        if(date.contains("SEP"))
            return 9;
        if(date.contains("OCT"))
            return 10;
        if(date.contains("NOV"))
            return 11;
        if(date.contains("DEC"))
            return 12;

        return -1;
    }
}

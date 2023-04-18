package com.smartapi.pojo;

import lombok.Getter;

@Getter
public class LoginResponse {
	String status;
	
	String message;
	
	Data data;
	
	String errorcode;

	public static String successResponse = "SUCCESS";
}

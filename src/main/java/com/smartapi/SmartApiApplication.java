package com.smartapi;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.angelbroking.smartapi.models.OrderParams;
import com.angelbroking.smartapi.utils.Constants;
import org.json.JSONObject;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartapi.pojo.LoginResponse;

import lombok.extern.log4j.Log4j2;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Log4j2
@EnableAsync
@EnableScheduling
public class SmartApiApplication {

	private static ConfigurableApplicationContext context;

	public static void main(String[] args) throws UnknownHostException {
		context = SpringApplication.run(SmartApiApplication.class, args);
		log.info("Started jar at address {}, Build {}", InetAddress.getLocalHost(), "11.11.1");
	}

	public static void restart() {
		ApplicationArguments args = context.getBean(ApplicationArguments.class);

		Thread thread = new Thread(() -> {
			context.close();
			context = SpringApplication.run(SmartApiApplication.class, args.getSourceArgs());
		});

		thread.setDaemon(false);
		thread.start();
	}
}

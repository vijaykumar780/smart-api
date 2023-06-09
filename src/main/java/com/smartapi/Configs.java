package com.smartapi;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.User;
import com.smartapi.pojo.Instrument;
import com.smartapi.pojo.Instruments;
import com.smartapi.pojo.PlacedOrders;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import org.springframework.context.annotation.PropertySources;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.time.LocalDate;
import java.util.*;

@Configuration
@PropertySources({
		@PropertySource(value = {"file:////Users/b0224854/Trade/configs.conf"}, ignoreResourceNotFound = true),
		@PropertySource(value = {"file:D:\\Trade\\tradeConfigs.conf"}, ignoreResourceNotFound = true),
		@PropertySource(value = {"file:////home/vijaykumarvijay886cloud2/Trade/tradeConfigs.conf"}, ignoreResourceNotFound = true),
		@PropertySource(value = {"file:////home/vijaykumarvijay886/Trade/tradeConfigs.conf"}, ignoreResourceNotFound = true),})
@Getter
@Log4j2
@Setter
public class Configs {

	@Bean
	ObjectMapper objectMapper() {
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		return objectMapper;
	}

	int maxLossEmailCount;
	// modify for each day
	boolean tradePlaced;

	long reInitLastEpoch = 0;

	boolean slHitSmsSent = false;

	@Value("${marketPrivateKey}")
	String marketPrivateKey;

	@Value("${tradingPrivateKey}")
	String tradingPrivateKey;

	@Value("${historyPrivateKey}")
	String historyPrivateKey;

	@Value("${totp}")
	String totp;

	@Value("${lotsToBuy}")
	String lotsToBuy;

	@Value("${sendTestMail}")
	String sendTestMail;

	@Value("${whatsappApiKey}")
	String whatsappApiKey;

	String tradingSmartConnectRefreshToken;

	@Value("${gmailPassword}")
	String gmailPassword;

	@Value("${awsAccessKey}")
	private String awsAccessKey;

	@Value("${awsSecretKey}")
	private String awsSecretKey;

	String marketSmartConnectRefreshToken;

	String historySmartConnectRefreshToken;

	String niftyToken;

	List<String> totps = new ArrayList<>();
	private String password = "8080";
    // japantokyo8
	@Bean
	PlacedOrders placedOrders() {
		return new PlacedOrders();
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

	//@Bean("tradingSmartConnect")
	public SmartConnect TradingSmartConnect() throws Exception, SmartAPIException {
		log.info("Setting trade placed");
		this.tradePlaced = false;
		log.info("Setting max loss email count");
		this.maxLossEmailCount = 50;

		log.info("Creating TradingSmartConnect");
		SmartConnect smartConnect = new SmartConnect();
		smartConnect.setApiKey(tradingPrivateKey);
		User user = smartConnect.generateSession("V122968", password, totp);
		log.info("User {}", user.toString());

		if (user.getAccessToken()==null) {
			Exception e = new Exception("Error in token creation");
			log.error("Error in token creation", e);
			throw e;
		}
		this.tradingSmartConnectRefreshToken = user.getRefreshToken();
		smartConnect.setAccessToken(user.getAccessToken());
		smartConnect.setUserId(user.getUserId());
		log.info("Created TradingSmartConnect. Token {}", user.getAccessToken());
		Thread.sleep(800);
		return smartConnect;
	}

	//@Bean("historySmartConnect")
	public SmartConnect historySmartConnect() throws Exception, SmartAPIException {
		log.info("Creating historySmartConnect. key {}", historyPrivateKey);
		SmartConnect smartConnect = new SmartConnect();
		smartConnect.setApiKey(historyPrivateKey);
		User user = smartConnect.generateSession("V122968", password, totp);
		log.info("User {}", user.toString());

		if (user.getAccessToken()==null) {
			Exception e = new Exception("Error in token creation");
			log.error("Error in token creation", e);
			throw e;
		}
		this.historySmartConnectRefreshToken = user.getRefreshToken();
		smartConnect.setAccessToken(user.getAccessToken());
		smartConnect.setUserId(user.getUserId());
		log.info("Created historySmartConnect. Token {}", user.getAccessToken());
		Thread.sleep(800);
		return smartConnect;
	}

	//@Bean("marketSmartConnect")
	public SmartConnect MarketSmartConnect() throws Exception, SmartAPIException {
		log.info("Creating MarketSmartConnect");
		SmartConnect smartConnect = new SmartConnect();
		smartConnect.setApiKey(marketPrivateKey);
		User user = smartConnect.generateSession("V122968", password, totp);
		log.info("User {}", user.toString());

		if (user.getAccessToken()==null) {
			Exception e = new Exception("Error in token creation");
			log.error("Error in token creation", e);
			throw e;
		}
		this.marketSmartConnectRefreshToken = user.getRefreshToken();

		smartConnect.setAccessToken(user.getAccessToken());
		smartConnect.setUserId(user.getUserId());
		log.info("Created MarketSmartConnect. Token {}", user.getAccessToken());
		Thread.sleep(800);
		return smartConnect;
	}

	@Bean
	public AWSCredentials credentials() {
		return new BasicAWSCredentials(awsAccessKey, awsSecretKey);
	}

	@Bean
	public AmazonSNSClient  amazonSNS() {
		return (AmazonSNSClient) AmazonSNSClientBuilder.standard()
				.withCredentials(new AWSStaticCredentialsProvider(credentials()))
				.withRegion("ap-south-1")
				.build();
	}
}

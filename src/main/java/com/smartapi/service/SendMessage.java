package com.smartapi.service;

import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.PublishRequest;
import com.smartapi.Configs;
import com.smartapi.Constants;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

@Service
@Log4j2
public class SendMessage {

	@Autowired
	private JavaMailSender javaMailSender;

	@Autowired
	Configs configs;

	@Autowired
	RestTemplate restTemplate;

	@Autowired
	AmazonSNSClient snsClient;

	int sns = 0;
	public void sendMessage(String message) {
		if (message != null && message.contains("Failed to")) {
			// reinit session repeated errors
			long epochNow = Instant.now().getEpochSecond();
			if (Math.abs(epochNow- configs.getReInitLastEpoch()) > 1800) {
				configs.setReInitLastEpoch(epochNow);
			} else {
				log.info("Skipping message for failure, as recently sent same mail");
				return;
			}
		}

		//log.info("Sending message: {}", message);
		if (sns==1) {
			snsClient.publish(new PublishRequest("arn:aws:sns:ap-south-1:801536992554:sms", message));
			log.info("Message sent");
		} else {
			SimpleMailMessage msg = new SimpleMailMessage();
			msg.setTo("vijaykumarvijay886@gmail.com");

			msg.setSubject("[ALGO]");
			msg.setText(message);
			msg.setFrom("vijaykumarvijay886@gmail.com");
			try {
				javaMailSender.send(msg);
				log.info("Email sent");
			} catch (Exception e) {
				log.error(Constants.IMP_LOG+"Error in sending mail ", e);
			}
		}
	}
}




package com.smartapi.service;

import com.smartapi.Configs;
import com.smartapi.Constants;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Service
@Log4j2
public class TradeAndEmailRefreshScheduler {

    @Autowired
    Configs configs;

    @Autowired
    SendMessage sendMessage;

    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void refreshPerDayTrade() {
        log.info(Constants.IMP_LOG+"setting TradePlaced false");
        configs.setTradePlaced(false);
        log.info(Constants.IMP_LOG+"TradePlaced is set false");
    }

    @Scheduled(fixedDelay = 1640000000)
    public void refreshEmail() throws UnknownHostException {

        //sendMessage.sendMessage("ALGO App started " + InetAddress.getLocalHost().toString());
        log.info(Constants.IMP_LOG+"Start Email sent");
    }
}

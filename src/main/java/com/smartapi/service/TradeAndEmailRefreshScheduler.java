package com.smartapi.service;

import com.smartapi.Configs;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Log4j2
public class TradeAndEmailRefreshScheduler {

    @Autowired
    Configs configs;

    @Autowired
    SendMessage sendMessage;

    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void refreshPerDayTrade() {
        log.info("setting TradePlaced false");
        configs.setTradePlaced(false);
        log.info("TradePlaced is set false");
    }

    @Scheduled(fixedDelay = 1640000000)
    public void refreshEmail() {
        sendMessage.sendMessage("App started");
        log.info("Start Email sent");
    }
}

package com.smartapi.service;

import com.smartapi.Configs;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;

@Service
@Log4j2
public class TokenRefresh {
    @Autowired
    SendMessage sendMessage;

    @Autowired
    Configs configs;

    /*
    SmartConnect tradingSmartConnect;


    SmartConnect marketSmartConnect;


    SmartConnect historySmartConnect;




   //@Scheduled(cron = "0 0/30 0-23 * * *") //(cron = "0 0/30 0-23 * * *")
    public void refreshSmartConnectToken() {
        boolean init = false;

        for (int i = 0; i <= 5; i++) {
            try {
                log.info("Refreshing trading smart connect token");
                TokenSet tokenSet = tradingSmartConnect.renewAccessToken(tradingSmartConnect.getAccessToken(),
                        configs.getTradingSmartConnectRefreshToken());
                tradingSmartConnect.setAccessToken(tokenSet.getAccessToken());
                configs.setTradingSmartConnectRefreshToken(tokenSet.getRefreshToken());
                Thread.sleep(1000);
                log.info("Refreshed trading smart connect token {}", tokenSet.getAccessToken());

                log.info("Refreshing market smart connect token");
                tokenSet = marketSmartConnect.renewAccessToken(marketSmartConnect.getAccessToken(),
                        configs.getMarketSmartConnectRefreshToken());
                marketSmartConnect.setAccessToken(tokenSet.getAccessToken());
                configs.setMarketSmartConnectRefreshToken(tokenSet.getRefreshToken());
                Thread.sleep(1000);

                log.info("Refreshed market smart connect token {}", tokenSet.getAccessToken());

                log.info("Refreshing history smart connect token");
                tokenSet = historySmartConnect.renewAccessToken(historySmartConnect.getAccessToken(),
                        configs.getHistorySmartConnectRefreshToken());
                historySmartConnect.setAccessToken(tokenSet.getAccessToken());
                configs.setHistorySmartConnectRefreshToken(tokenSet.getRefreshToken());
                Thread.sleep(1000);
                log.info("Refreshed history smart connect token {}", tokenSet.getAccessToken());
                init = true;
                break;
            } catch (Exception e) {
                log.error("Error in refresh token, retrying {}", i);
                reInitSession();
            }
        }
        if (init == false) {
            sendEmail.sendMail("Failed to refresh token");
        }
    }

    //@Scheduled(cron = "0 5 * * * *")
    public void reInitSession() {
        boolean init = false;
        for (int i = 0; i <= 5; i++) {
            try {
                log.info("Re-initiating session");
                configs.TradingSmartConnect();
                configs.MarketSmartConnect();
                configs.historySmartConnect();
                log.info("Re-initiated session");
                init = true;
                break;
            } catch (Exception e) {
                log.error("Error in re-init session, Retrying");
            }
        }
        if (init == false) {
            sendEmail.sendMail("Failed to re init session");
        }
    }
*/
    @Scheduled(cron = "0 45 8 * * ?")
    public void reInitEmail() {
        int ct = 200;
        //sendMessage.sendMessage("Re init email count threshold and SymbolExitedFromScheduler" + ct);
        configs.setMaxLossEmailCount(ct);
        configs.setSymbolExitedFromScheduler(new ArrayList<>());
        configs.setOiTradeMap(new HashMap<>());
        configs.setOiBasedTradePlaced(false);
        configs.setSymbolMap(new HashMap<>());
        configs.setTradedOptions(new ArrayList<>());
        configs.setTotalMaxOrdersAllowed(300);
        configs.setSensxSymbolData(new HashMap<>());
        configs.setGmailPassSentCount(0);
        configs.setMaxOiBasedTradePlaced(false);
        log.info("Re inited email count threshold");
    }

    @Scheduled(cron = "0 15 16 * * ?")
    public void memoryClear() {
        configs.setSymbolExitedFromScheduler(new ArrayList<>());
        configs.setOiTradeMap(new HashMap<>());
        configs.setOiBasedTradePlaced(false);
        configs.setSymbolMap(new HashMap<>());
        configs.setTradedOptions(new ArrayList<>());
        configs.setSensxSymbolData(new HashMap<>());
        log.info("Cleared memory");
    }
}

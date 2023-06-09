package com.smartapi.service;

import com.smartapi.Configs;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
        int ct = 80;
        sendMessage.sendMessage("Re init email count threshold " + ct);
        configs.setMaxLossEmailCount(ct);
        log.info("Re inited email count threshold");
    }
}

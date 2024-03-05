package com.smartapi.service;

import com.smartapi.Configs;
import com.smartapi.Constants;
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
        configs.setSymbolToStrikeMap(new HashMap<>());
        configs.setIronManTradePlaced(false);
        log.info(Constants.IMP_LOG+"Re inited email count threshold");
    }

    @Scheduled(cron = "0 45 15 * * ?")
    public void memoryClear() {
        configs.setSymbolExitedFromScheduler(new ArrayList<>());
        configs.setOiTradeMap(new HashMap<>());
        configs.setOiBasedTradePlaced(false);
        configs.setSymbolMap(new HashMap<>());
        configs.setTradedOptions(new ArrayList<>());
        configs.setSensxSymbolData(new HashMap<>());
        configs.setIronManTradePlaced(false);
        log.info(Constants.IMP_LOG+"Cleared memory");
    }
}

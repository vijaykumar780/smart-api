package com.smartapi.pojo;

import com.smartapi.Configs;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@Builder
@ToString
public class SystemConfigs {
    private int oiPercent;

    private int oiBasedTradeQtyNifty;

    private int oiBasedTradeQtyFinNifty;

    private int oiBasedTradeMidcapQty;

    private int oiBasedTradeBankNiftyQty;

    private int oiBasedTradeSensexQty;


    private int niftyLotSize;

    private int finniftyLotSize;

    private int midcapNiftyLotSize;

    private int bankNiftyLotSize;

    private int sensexLotSize;


    private int maxLossAmount;

    private List<String> oiTradeMap;

    private Boolean oiBasedTradePlaced;

    private List<String> tradedOptions;

    private Integer gmailPassSentCount;

    private int totalMaxOrdersAllowed;

    private int totalSymbolsLoaded;

    private int mtm;

    private int maxProfit;

    private int memoryRemaining;

    private boolean isMaxOiBasedTradePlaced;

    private String build;

    private Integer rrProfit;
    private Integer rrLoss;
    private boolean rr2Allowed;
    private boolean rr2TradePlaced;
}

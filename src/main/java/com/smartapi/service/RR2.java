package com.smartapi.service;

import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.utils.Constants;
import com.smartapi.Configs;
import com.smartapi.pojo.OiTrade;
import com.smartapi.pojo.OptionData;
import com.smartapi.pojo.SymbolData;
import lombok.extern.log4j.Log4j2;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Log4j2
@Service
public class RR2 {

    private RestTemplate restTemplate;

    @Autowired
    private SendMessage sendMessage;

    @Autowired
    private CommonService commonService;

    @Autowired
    private StopAtMaxLossScheduler stopAtMaxLossScheduler;

    private List<SymbolData> symbolDataList;

    @Autowired
    private Configs configs;

    private Map<String, Integer> oiMap;

    private String marketDataUrl = "https://apiconnect.angelbroking.com/rest/secure/angelbroking/market/v1/quote/";

    private String SENSEX = "SENSEX";

    private String BSE_NFO = "BFO";
    private String NSE_NFO = "NFO";

    private boolean isNiftyExpiry = false;
    private boolean isBankNiftyExpiry = false;
    private boolean isFinNiftyExpiry = false;
    private boolean isMidcapNiftyExpiry = false;

    private boolean isSensexExpiry = false;

    private boolean isOiCrossTradeAllowed = false;

    @Scheduled(cron = "0 50 8 * * ?")
    public void reInitEmail() {
        int success = 0;
        for (int i = 0; i < 10; i++) {
            int status = init();
            if (status == 1) {
                success = 1;
                break;
            }
        }
        if (success == 1) {
            StringBuilder content = new StringBuilder();
            content.append("Total symbols loaded: " + configs.getSymbolDataList().size());
            content.append("\n");

            content.append("Max Oi based trade placed: " + configs.isMaxOiBasedTradePlaced() + "\n");
            content.append("Gmail password sent count: " + configs.getGmailPassSentCount() + "\n");
            content.append("Total max orders allowed: " + configs.getTotalMaxOrdersAllowed() + "\n");
            content.append("Traded Options: " + configs.getTradedOptions() + "\n");
            content.append("Oi Based trade placed: " + configs.getOiBasedTradePlaced() + "\n");

            //sendMessage.sendMessage(content.toString());
            log.info(com.smartapi.Constants.IMP_LOG+"Data loaded of symbols");
        } else {
            sendMessage.sendMessage("Failed data loaded for symbols");
            log.error(com.smartapi.Constants.IMP_LOG+"Failed data loaded of symbols");
        }
    }

    public int init() {
        int success = 0;
        oiMap = new HashMap<>();
        configs.setSymbolMap(new HashMap<>());
        configs.setOiTradeMap(new HashMap<>());
        configs.setOiBasedTradePlaced(false);
        configs.setTradedOptions(new ArrayList<>());
        configs.setSensxSymbolData(new HashMap<>());
        configs.setSymbolToStrikeMap(new HashMap<>());

        log.info(com.smartapi.Constants.IMP_LOG + "Initializing rest template");
        restTemplate = new RestTemplateBuilder().setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        log.info(com.smartapi.Constants.IMP_LOG + "Rest template initialized");
        log.info(com.smartapi.Constants.IMP_LOG + "Fetching symbols");
        HttpEntity<String> httpEntity = new HttpEntity<String>("ip");
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
                    HttpMethod.GET, httpEntity, String.class);
            success = 1;

        } catch (Exception e) {
            response = restTemplate.exchange("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
                    HttpMethod.GET, httpEntity, String.class);
            log.error(com.smartapi.Constants.IMP_LOG + "Failed fetching symbols, retrying");
            success = 0;
        }

        log.info(com.smartapi.Constants.IMP_LOG + "Fetched symbols");

        int cnt = 0;
        try {
            JSONArray jsonArray = new JSONArray(response.getBody());

            symbolDataList = new ArrayList<>();

            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMMyyyy"));
            int nonMatchedExpiries = 0;
            int matchedExpiries = 0;
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject ob = jsonArray.getJSONObject(i);
                if (ob.optString("expiry") == null || ob.optString("expiry").isEmpty()
                        || ob.optString("strike") == null || ob.optString("strike").isEmpty()) {
                    continue;
                }
                if (ob.optString("expiry").equalsIgnoreCase(today)) {
                    SymbolData symbolData = SymbolData.builder()
                            .symbol(ob.getString("symbol"))
                            .token(ob.getString("token"))
                            .name(ob.getString("name"))
                            .expiry(commonService.getLocalDate(ob.getString("expiry")))
                            .expiryString(ob.getString("expiry"))
                            .strike(((int) Double.parseDouble(ob.optString("strike"))) / 100)
                            .lotSize(ob.optString("lotsize", "1"))
                            .build();
                    cnt++;
                    if (symbolData.getSymbol() != null && symbolData.getSymbol().startsWith("NIFTY")) {
                        isNiftyExpiry = true;
                    }
                    if (symbolData.getSymbol() != null && symbolData.getSymbol().startsWith("FINNIFTY")) {
                        isFinNiftyExpiry = true;
                    }
                    if (symbolData.getSymbol() != null && symbolData.getSymbol().startsWith("MIDCPNIFTY")) {
                        isMidcapNiftyExpiry = true;
                    }
                    if (symbolData.getSymbol() != null && symbolData.getSymbol().startsWith("BANKNIFTY")) {
                        isBankNiftyExpiry = true;
                    }
                    if (Arrays.asList("NIFTY", "FINNIFTY", "MIDCPNIFTY", "BANKNIFTY", SENSEX).contains(symbolData.getName())
                            && ("NFO".equals(ob.optString("exch_seg")) || BSE_NFO.equals(ob.optString("exch_seg")))) {
                        symbolDataList.add(symbolData);
                        matchedExpiries++;

                    }
                }
            }

            //symbolDataList.add(SymbolData.builder().expiryString("03NOV2023").symbol("smbl").strike(1500).name("symb").token("tkn").build());
            log.info(com.smartapi.Constants.IMP_LOG + "Processed {} symbols. Oi change percent {}. Matched Expiries {}, Non match expiries {} for today", symbolDataList.size(), configs.getOiPercent(),
                    matchedExpiries, jsonArray.length() - matchedExpiries);
            log.info(com.smartapi.Constants.IMP_LOG + "Expiry today Nifty {}, Finnifty {}, midcap {}, bankNifty {}", isNiftyExpiry,
                    isFinNiftyExpiry, isMidcapNiftyExpiry, isBankNiftyExpiry);

            jsonArray = null;
        } catch (Exception e) {
            log.error(com.smartapi.Constants.IMP_LOG + "Error in processing symbols at count {}, {}", cnt, e.getMessage());
        }
        if (success == 1) {
            configs.setSymbolDataList(symbolDataList);
        }

        return success;
    }

    @Scheduled(fixedDelay = 60000)
    public void rr2() throws Exception {

        LocalTime localStartTimeMarket = LocalTime.of(10, 15, 0);
        LocalTime localEndTime = LocalTime.of(20, 20, 1);
        LocalTime now1 = LocalTime.now();
        if (!configs.isRR2TradeAllowed()) {
            log.info("isRR2TradeAllowed is false, skipping");
            return;
        }

        if (LocalTime.now().getHour()!=10) {
            log.info("Skipping rr2 as Hour is not 10");
            return;
        }

        if (configs.isRR2TradePlaced()) {
            log.info("Skipping as rr2 trade already processed");
            return;
        }

        if (!(now1.isAfter(localStartTimeMarket) && now1.isBefore(localEndTime))) {
            log.info("Skipping rr2 as Time not in range");
            return;
        }
        if (configs.getSymbolDataList() == null || configs.getSymbolDataList().isEmpty()) {
            log.info(com.smartapi.Constants.IMP_LOG+"Loading symbols");
            init();
            log.info(com.smartapi.Constants.IMP_LOG+"Loaded symbols");
        }

        configs.setRR2TradePlaced(true);
        log.info("Set rr2 as true");
        sendMessage.sendMessage("Set rr2 as true");

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMMyyyy"));
        today = today.substring(0,5) + today.substring(7);
        today = today.toUpperCase();



        LocalDate expiryDateNifty = commonService.getExpiryDate(DayOfWeek.THURSDAY); // use wednesday if holiday on exp
        LocalDate expiryDateFinNifty = commonService.getExpiryDate(DayOfWeek.TUESDAY); // if holiday then skip its monday exp as midcap there
        LocalDate expiryDateMidcapNifty = commonService.getExpiryDate(DayOfWeek.MONDAY); // use friday exp
        LocalDate expiryDateBankNifty = commonService.getExpiryDate(DayOfWeek.WEDNESDAY);
        LocalDate expirySenSex = commonService.getExpiryDate(DayOfWeek.FRIDAY);

        double niftyLtp = 0;
        double finniftyLtp = 0;
        double midcapLtp = 0;
        double bankNiftyLtp = 0;
        double sensxLtp = 0;

        try {
            niftyLtp = commonService.getOi("99926000", "NSE");
            bankNiftyLtp = commonService.getOi("99926009", "NSE");

            finniftyLtp = commonService.getOi("99926037", "NSE");
            midcapLtp = commonService.getOi("99926074", "NSE");
            sensxLtp = commonService.getOi("99919000", "BSE");
            if (midcapLtp == -1) {
                midcapLtp = configs.getMidcapNiftyValue();
            }
            log.info("Index values nifty: {}\n finnifty: {}\n midcapnifty {}\n BankNifty {}\n Sensx {}\n  Nifty exp {}\n fnnifty exp {}\n midcap exp {}\n BankNifty exp {}\n, sensx exp {}",
                    niftyLtp, finniftyLtp, midcapLtp, bankNiftyLtp, sensxLtp, expiryDateNifty, expiryDateFinNifty, expiryDateMidcapNifty, expiryDateBankNifty, expirySenSex);

        } catch (InterruptedException e) {
            log.error("Error fetch index ltp", e);
        }

        int oi;
        int niftyDiff = 500; // index value diff
        int finniftyDiff = 500;
        int midcapDiff = 500;
        int bankNiftyDiff = 800;
        int senSxDiff = 1000;
        StringBuilder email = new StringBuilder();
        List<SymbolData> symbols = configs.getSymbolDataList();
        int totalCeOi = 0;
        int totalPeOi = 0;
        for (SymbolData symbolData : symbols) {
            if (!configs.getSymbolToStrikeMap().containsKey(symbolData.getSymbol())) {
                configs.getSymbolToStrikeMap().put(symbolData.getSymbol(), symbolData.getStrike());
            }

            try {
                if (symbolData.getName().equals("NIFTY") && Math.abs(symbolData.getStrike() - niftyLtp) <= niftyDiff) {
                    String name = "";
                    name = name + "NIFTY_";
                    name = name + symbolData.getStrike() + "_" + (symbolData.getSymbol().endsWith("CE") ? "CE" : "PE");
                    if (!configs.getSymbolMap().containsKey(name)) {
                        configs.getSymbolMap().put(name, symbolData);
                    }
                    oi = commonService.getOi(symbolData.getToken(), "NFO");
                    oiMap.put(symbolData.getSymbol(), oi);
                }

                if (symbolData.getName().equals("FINNIFTY") && Math.abs(symbolData.getStrike() - finniftyLtp) <= finniftyDiff) {
                    String name = "";
                    name = name + "FINNIFTY_";
                    name = name + symbolData.getStrike() + "_" + (symbolData.getSymbol().endsWith("CE") ? "CE" : "PE");
                    if (!configs.getSymbolMap().containsKey(name)) {
                        configs.getSymbolMap().put(name, symbolData);
                    }
                    oi = commonService.getOi(symbolData.getToken(), "NFO");
                        oiMap.put(symbolData.getSymbol(), oi);
                }

                if (symbolData.getName().equals("MIDCPNIFTY") && Math.abs(symbolData.getStrike() - midcapLtp) <= midcapDiff) {
                    String name = "";
                    name = name + "MIDCPNIFTY_";
                    name = name + symbolData.getStrike() + "_" + (symbolData.getSymbol().endsWith("CE") ? "CE" : "PE");
                    if (!configs.getSymbolMap().containsKey(name)) {
                        configs.getSymbolMap().put(name, symbolData);
                    }
                    oi = commonService.getOi(symbolData.getToken(), "NFO");
                    oiMap.put(symbolData.getSymbol(), oi);
                }

                if (symbolData.getName().equals("BANKNIFTY") && Math.abs(symbolData.getStrike() - bankNiftyLtp) <= bankNiftyDiff) {
                    String name = "";
                    name = name + "BANKNIFTY_";
                    name = name + symbolData.getStrike() + "_" + (symbolData.getSymbol().endsWith("CE") ? "CE" : "PE");
                    if (!configs.getSymbolMap().containsKey(name)) {
                        configs.getSymbolMap().put(name, symbolData);
                    }
                    oi = commonService.getOi(symbolData.getToken(), "NFO");
                        oiMap.put(symbolData.getSymbol(), oi);
                }

                // sensex skipped
            } catch (Exception e) {
                log.error(com.smartapi.Constants.IMP_LOG+"Error in fetching oi of symbol {}", symbolData.getSymbol(), e);
            }
        }
        log.info("Symbol map and oi map loaded");

        log.info("Oi Map is");
        String indexExpiry = "";
        if (isNiftyExpiry) {
            indexExpiry = "NIFTY";
        } else if (isBankNiftyExpiry) {
            indexExpiry = "BANKNIFTY";
        } else if (isFinNiftyExpiry) {
            indexExpiry = "FINNIFTY";
        } else if (isMidcapNiftyExpiry) {
            indexExpiry = "MIDCPNIFTY";
        } else {
            log.info("no expiry today, skipping");
        }

        for (Map.Entry<String, Integer> entry : oiMap.entrySet()) {
            log.info("{} : {}", entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, SymbolData> entry : configs.getSymbolMap().entrySet()) {
            if (!entry.getKey().startsWith(indexExpiry)) {
                continue;
            }
            int st = entry.getValue().getStrike();
            /*if (isNiftyExpiry && Math.abs((double) st - niftyLtp)>250) {
                continue;
            }
            if (isFinNiftyExpiry && Math.abs((double) st - finniftyLtp)>250) {
                continue;
            }
            if (isMidcapNiftyExpiry && Math.abs((double) st - midcapLtp)>150) {
                continue;
            }
            if (isBankNiftyExpiry && Math.abs((double) st - bankNiftyLtp)>400) {
                continue;
            }*/

            if (entry.getKey().endsWith("CE")) {
                totalCeOi += oiMap.get(entry.getValue().getSymbol());
            } else {
                totalPeOi += oiMap.get(entry.getValue().getSymbol());
            }
        }

        String sellOptType = "";
        if (totalCeOi>totalPeOi) {
            sellOptType = "CE";
        } else {
            sellOptType = "PE";
        }

        log.info("Total ce oi : {}, pe oi : {}. sellOptType: {}", totalCeOi, totalPeOi, sellOptType);
        int step;
        int startStrike = 0;
        String initialSymbol = "";
        if (isNiftyExpiry) {
            step = commonService.getIndexStepSize("NIFTY");
            startStrike = ((int) niftyLtp / step) * step;
            initialSymbol = "NIFTY"+"_"+startStrike+"_"+sellOptType;
        } else if (isFinNiftyExpiry) {
            step = commonService.getIndexStepSize("FINNIFTY");
            startStrike = ((int) finniftyLtp / step) * step;
            initialSymbol = "FINNIFTY"+"_"+startStrike+"_"+sellOptType;
        } else if (isBankNiftyExpiry) {
            step = commonService.getIndexStepSize("BANKNIFTY");
            startStrike = ((int) bankNiftyLtp / step) * step;
            initialSymbol = "BANKNIFTY"+"_"+startStrike+"_"+sellOptType;
        } else if (isMidcapNiftyExpiry) {
            step = commonService.getIndexStepSize("MIDCPNIFTY");
            startStrike = ((int) midcapLtp / step) * step;
            initialSymbol = "MIDCPNIFTY"+"_"+startStrike+"_"+sellOptType;
        }
        SymbolData sellSymbol = fetchSellSymbol(configs.getSymbolMap().get(initialSymbol).getSymbol());
        placeOrders(sellSymbol);

        System.gc();
    }

    public void placeOrders(SymbolData tradeSymbol) throws Exception {
        int qty;
        String indexName = commonService.getIndexName(tradeSymbol.getSymbol());
        int lotSize = Integer.parseInt(tradeSymbol.getLotSize());
        int maxQty = 500;
        if (indexName.equals("MIDCPNIFTY")) {
            maxQty = 4200;
        } else if (indexName.equals("BANKNIFTY")) {
            maxQty = 900;
        } else if (indexName.equals(SENSEX)) {
            maxQty = 1000;
        } else {
            maxQty = 1800;
        }
        log.info("Index name: {}", indexName);
        log.info("Max qty per order {}", maxQty);
        int i;

        SymbolData sellSymbolData = tradeSymbol;
        Double sellLtp = commonService.getLtp(sellSymbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO : NSE_NFO);
        String optionType = tradeSymbol.getSymbol().endsWith("CE") ? "CE" : "PE";
        int strikeDiff;
        log.info("Sell symbol: {}, ltp: {}", sellSymbolData.getSymbol(), sellLtp);

        strikeDiff = 100; // used for buy order
        if (indexName.equals("MIDCPNIFTY")) {
            strikeDiff = 50;
        } else if (indexName.equals("BANKNIFTY")) {
            strikeDiff = 300;
        } else if (indexName.equals(SENSEX)) {
            strikeDiff = 300;
        } else {
            strikeDiff = 150;
        }

        int buyStrike = optionType.equals("CE") ? (sellSymbolData.getStrike() + strikeDiff) :
                (sellSymbolData.getStrike() - strikeDiff);
        SymbolData buySymbolData = configs.getSymbolMap().get(indexName + "_" + buyStrike + "_" + optionType);
        Double buyLtp = commonService.getLtp(buySymbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO : NSE_NFO);
        log.info("Buy symbol: {}, ltp: {}", buySymbolData.getSymbol(), buyLtp);

        qty = (int) ((double) configs.getRrProfit() / (sellLtp - buyLtp));
        qty = (qty / lotSize) * lotSize;
        qty = qty + lotSize;
        sendMessage.sendMessage(String.format("Sell symbol: %s. Buy symbol: %s. Qty: %d", sellSymbolData.getSymbol(), buySymbolData.getSymbol(), qty));

        log.info("Total Qty to sell: {}", qty);
        double maxLoss = configs.getMaxLossAmount();

        int fullBatches = qty / maxQty;
        int remainingQty = qty % maxQty;
        remainingQty = (remainingQty % (int) lotSize == 0) ? remainingQty : remainingQty - (remainingQty % (int) lotSize);
        log.info("Buy: full batch: {}, remain qty: {}", fullBatches, remainingQty);

        List<String> tradedOptions = configs.getTradedOptions();
        tradedOptions.add(sellSymbolData.getSymbol());
        tradedOptions.add(buySymbolData.getSymbol());
        configs.setTradedOptions(tradedOptions);
        String opt;
        // place full and remaining orders.
        for (i = 0; i < fullBatches; i++) {
            Order order = stopAtMaxLossScheduler.placeOrder(buySymbolData.getSymbol(), buySymbolData.getToken(), buyLtp, maxQty, Constants.TRANSACTION_TYPE_BUY, 0.0);
            if (order != null) {
                opt = String.format(com.smartapi.Constants.IMP_LOG + "Buy order placed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                log.info(opt);
                sendMessage.sendMessage(opt);
            } else {
                opt = String.format(com.smartapi.Constants.IMP_LOG + "Buy order failed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                log.info(opt);
                sendMessage.sendMessage(opt);
            }
        }
        if (remainingQty > 0) {
            Order order = stopAtMaxLossScheduler.placeOrder(buySymbolData.getSymbol(), buySymbolData.getToken(), buyLtp, remainingQty, Constants.TRANSACTION_TYPE_BUY, 0.0);
            if (order != null) {
                opt = String.format(com.smartapi.Constants.IMP_LOG + "Buy order placed for %s, qty %d", buySymbolData.getSymbol(), remainingQty);
                log.info(opt);
                sendMessage.sendMessage(opt);
            } else {
                opt = String.format(com.smartapi.Constants.IMP_LOG + "Buy order failed for %s, qty %d", buySymbolData.getSymbol(), remainingQty);
                log.info(opt);
                sendMessage.sendMessage(opt);
            }
        }
        log.info("Buy orders placed");

        for (i = 0; i < fullBatches; i++) {
            Order sellOrder = stopAtMaxLossScheduler.placeOrder(sellSymbolData.getSymbol(), sellSymbolData.getToken(), sellLtp, maxQty, Constants.TRANSACTION_TYPE_SELL, 0.0);
            if (sellOrder != null) {
                opt = String.format(com.smartapi.Constants.IMP_LOG + "Sell order placed for %s, qty %d, Price %f", sellSymbolData.getSymbol(), maxQty, sellLtp);
                log.info(opt);
                sendMessage.sendMessage(opt);
            } else {
                opt = String.format(com.smartapi.Constants.IMP_LOG + "Sell order failed for %s, qty %d Price %f", sellSymbolData.getSymbol(), maxLoss, sellLtp);
                log.info(opt);
                sendMessage.sendMessage(opt);
            }
        }
        if (remainingQty > 0) {
            Order sellOrder = stopAtMaxLossScheduler.placeOrder(sellSymbolData.getSymbol(), sellSymbolData.getToken(), sellLtp, remainingQty, Constants.TRANSACTION_TYPE_SELL, 0.0);
            if (sellOrder != null) {
                opt = String.format(com.smartapi.Constants.IMP_LOG + "Sell order placed for %s, qty %d, Price %f", sellSymbolData.getSymbol(), remainingQty, sellLtp);
                log.info(opt);
                sendMessage.sendMessage(opt);
            } else {
                opt = String.format(com.smartapi.Constants.IMP_LOG + "Sell order failed for %s, qty %d Price %f", sellSymbolData.getSymbol(), maxLoss, sellLtp);
                log.info(opt);
                sendMessage.sendMessage(opt);
            }
        }

        log.info("Sell orders placed");
        log.info("Finished RR2 trade");
    }

    private SymbolData fetchSellSymbol(String tradeSymbol) {
        try {
            int i;

            String indexName = commonService.getIndexName(tradeSymbol);
            String optionType = tradeSymbol.endsWith("CE") ? "CE" : "PE";

            SymbolData symbolData = commonService.getSymbolData(tradeSymbol);
            int strike = symbolData.getStrike();
            int step = commonService.getIndexStepSize(indexName);

            log.info("Step for index: {}", step);

            Double ltpLimit;
            if (indexName.equals("MIDCPNIFTY")) {
                ltpLimit = 9.0;
            } else if (indexName.equals("BANKNIFTY")) {
                ltpLimit = 30.0;
            } else if (indexName.equals(SENSEX)) {
                ltpLimit = 50.0;
            } else {
                ltpLimit = 15.0;
            }
            log.info("Ltp limit {}", ltpLimit);

            for (i = 0; i < 50; i++) {
                Double ltp = commonService.getLtp(symbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO: NSE_NFO);
                if (ltp <= ltpLimit) {
                    return symbolData;
                }
                // find next symbol
                if ("CE".equals(optionType)) {
                    strike = strike + step;
                } else {
                    strike = strike - step;
                }
                symbolData = configs.getSymbolMap().get(indexName+"_"+ strike +"_"+optionType);
            }
        } catch (InterruptedException e) {
            log.error(com.smartapi.Constants.IMP_LOG+"Error fetching sell symbol");
        }
        return null;
    }
}

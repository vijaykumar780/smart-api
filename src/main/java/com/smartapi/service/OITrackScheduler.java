package com.smartapi.service;

import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.utils.Constants;
import com.smartapi.Configs;
import com.smartapi.pojo.OiTrade;
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

import javax.annotation.PostConstruct;
import java.time.*;
import java.util.*;

@Log4j2
@Service
public class OITrackScheduler {

    private RestTemplate restTemplate;

    @Autowired
    private SendMessage sendMessage;

    @Autowired
    private StopAtMaxLossScheduler stopAtMaxLossScheduler;

    private List<SymbolData> symbolDataList;

    @Autowired
    private Configs configs;

    private Map<String, Integer> oiMap;

    private String marketDataUrl = "https://apiconnect.angelbroking.com/rest/secure/angelbroking/market/v1/quote/";

    // oi based trade
    double diffInitial = 7.0;
    double finalDiff = 13.0;

    int ceCount = 0;
    int peCount = 0;
    @PostConstruct
    public void init() {
        oiMap = new HashMap<>();
        configs.setSymbolMap(new HashMap<>());
        configs.setOiTradeMap(new HashMap<>());
        configs.setOiBasedTradePlaced(false);

        log.info("Initializing rest template");
        restTemplate = new RestTemplateBuilder().setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        log.info("Rest template initialized");
        log.info("Fetching symbols");
        HttpEntity<String> httpEntity = new HttpEntity<String>("ip");
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
                    HttpMethod.GET, httpEntity, String.class);
        } catch (Exception e) {
            response = restTemplate.exchange("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
                    HttpMethod.GET, httpEntity, String.class);
            log.error("Failed fetching symbols, retrying");
        }
        log.info("Fetched symbols");
        int startIndex = response.toString().indexOf("[");
        int endINdex = response.toString().indexOf(",[Server");
        int cnt = 0;
        try {
            JSONArray jsonArray = new JSONArray(response.toString().substring(startIndex, endINdex));

            symbolDataList = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject ob = jsonArray.getJSONObject(i);
                if (ob.optString("expiry") == null || ob.optString("expiry").isEmpty()
                        || ob.optString("strike") == null || ob.optString("strike").isEmpty()) {
                    continue;
                }
                SymbolData symbolData = SymbolData.builder()
                        .symbol(ob.getString("symbol"))
                        .token(ob.getString("token"))
                        .name(ob.getString("name"))
                        .expiry(getLocalDate(ob.getString("expiry")))
                        .strike(((int) Double.parseDouble(ob.optString("strike"))) / 100)
                        .build();
                cnt++;
                if (Arrays.asList("NIFTY", "FINNIFTY").contains(symbolData.getName())
                        && "NFO".equals(ob.optString("exch_seg"))) {
                    symbolDataList.add(symbolData);
                    if (!oiMap.containsKey(symbolData.getName())) {
                        //oiMap.put(symbolData.getSymbol(), 100);
                    }
                }
            }
            symbolDataList.sort(new Comparator<SymbolData>() {
                @Override
                public int compare(SymbolData o1, SymbolData o2) {
                    if (o1.getStrike() < o2.getStrike()) {
                        return -1;
                    } else {
                        return 1;
                    }
                }
            });
            symbolDataList.sort(new Comparator<SymbolData>() {
                @Override
                public int compare(SymbolData o1, SymbolData o2) {
                    if (o1.getSymbol().endsWith("CE")) {
                        return 1;
                    } else {
                        return -1;
                    }
                }
            });
            log.info("Processed symbols. Oi change percent {}", configs.getOiPercent());
        } catch (Exception e) {
            log.error("Error in processing symbols at count {}", e, cnt);
        }


    }

    private LocalDate getLocalDate(String expiry) {
        Month month = Month.DECEMBER;
        String mon = expiry.substring(2, 5);
        if (mon.equals("JAN")) {
            month = Month.JANUARY;
        }
        if (mon.equals("FEB")) {
            month = Month.FEBRUARY;
        }
        if (mon.equals("MAR")) {
            month = Month.MARCH;
        }
        if (mon.equals("APR")) {
            month = Month.APRIL;
        }
        if (mon.equals("MAY")) {
            month = Month.MAY;
        }
        if (mon.equals("JUN")) {
            month = Month.JUNE;
        }
        if (mon.equals("JUL")) {
            month = Month.JULY;
        }
        if (mon.equals("AUG")) {
            month = Month.AUGUST;
        }
        if (mon.equals("SEP")) {
            month = Month.SEPTEMBER;
        }
        if (mon.equals("OCT")) {
            month = Month.OCTOBER;
        }
        if (mon.equals("NOV")) {
            month = Month.NOVEMBER;
        }
        if (mon.equals("DEC")) {
            month = Month.DECEMBER;
        }
        return LocalDate.of(Integer.parseInt(expiry.substring(5)), month, Integer.valueOf(expiry.substring(0, 2)));

    }

    @Scheduled(fixedDelay = 60000)
    public void tradeOnBasisOfOi() {
        /*
        if total ce oi surpass total pe oi for some specific strike, then initiate a trade. sold option whose oi is larger after surpass
        incident found on today, when 19600 pe oi surpassed 19600 ce oi and 19600 pe became 0 from 12 to 0.
        similarly for 19650 strike.
         */

        LocalTime localStartTimeMarket = LocalTime.of(12, 40, 0);
        LocalTime localEndTime = LocalTime.of(15, 10, 1);
        LocalTime now1 = LocalTime.now();
        if (!(now1.isAfter(localStartTimeMarket) && now1.isBefore(localEndTime))) {
            return;
        }

        LocalDate expiryDateNifty = getExpiryDate(DayOfWeek.THURSDAY);
        LocalDate expiryDateFinNifty = getExpiryDate(DayOfWeek.TUESDAY);
        double niftyLtp = 0;
        double finniftyLtp = 0;
        try {
            niftyLtp = getOi("26000", "NSE");
            finniftyLtp = configs.getFinniftyValue();
            log.info("Using rough index values nifty: {}, finnifty: {}. Nifty exp {}, fnnifty exp {}",
                    niftyLtp, finniftyLtp, expiryDateNifty, expiryDateFinNifty);

        } catch (InterruptedException e) {
            log.error("Error fetch index ltp", e);
        }

        int oi;
        int diff = 500; // index value diff
        int finniftyDiff = 2000;
        StringBuilder email = new StringBuilder();
        for (SymbolData symbolData : symbolDataList) {
            try {
                if (symbolData.getName().equals("NIFTY") && expiryDateNifty.equals(symbolData.getExpiry()) && Math.abs(symbolData.getStrike() - niftyLtp) <= diff) {
                    String name = "";
                    name = name + "NIFTY_";
                    name = name + symbolData.getStrike() + "_" + (symbolData.getSymbol().endsWith("CE") ? "CE" : "PE");
                    if (!configs.getSymbolMap().containsKey(name)) {
                        configs.getSymbolMap().put(name, symbolData);
                    }
                    oi = getOi(symbolData.getToken(), "NFO");
                    if (oi == -1) {
                        continue;
                    }

                    if (oiMap.containsKey(symbolData.getSymbol())) {
                        double changePercent;
                        if (oiMap.get(symbolData.getSymbol()) == 0) {
                            changePercent = 0;
                        } else {
                            changePercent = ((double) (oi - oiMap.get(symbolData.getSymbol())) / (double) oiMap.get(symbolData.getSymbol())) * 100.0;
                        }
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Change: %d Change percent: %f Symbol: %s", "NIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, oi - oiMap.get(symbolData.getSymbol()), changePercent, symbolData.getSymbol());

                        if (Math.abs(changePercent) >= configs.getOiPercent() && oi > 500000 && LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY)) {
                            email.append(response);
                            email.append("\n");
                        } else if ((Math.abs(changePercent) >= configs.getOiPercent())) {
                            log.info("{} has change % above oi percent", symbolData.getSymbol());
                        }
                        oiMap.put(symbolData.getSymbol(), oi);
                        log.info("OI Data | {}", response);
                    } else {
                        oiMap.put(symbolData.getSymbol(), oi);
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Symbol: %s", "NIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, symbolData.getSymbol());

                        log.info("OI Data | {}", response);
                    }

                } else if (symbolData.getName().equals("FINNIFTY") && expiryDateFinNifty.equals(symbolData.getExpiry()) && Math.abs(symbolData.getStrike() - finniftyLtp) <= finniftyDiff) {
                    String name = "";
                    name = name + "FINNIFTY_";
                    name = name + symbolData.getStrike() + "_" + (symbolData.getSymbol().endsWith("CE") ? "CE" : "PE");
                    if (!configs.getSymbolMap().containsKey(name)) {
                        configs.getSymbolMap().put(name, symbolData);
                    }
                    oi = getOi(symbolData.getToken(), "NFO");

                    if (oi == -1) {
                        continue;
                    }
                    if (oiMap.containsKey(symbolData.getSymbol())) {
                        double changePercent;
                        if (oiMap.get(symbolData.getSymbol()) == 0) {
                            changePercent = 0;
                        } else {
                            changePercent = ((double) (oi - oiMap.get(symbolData.getSymbol())) / (double) oiMap.get(symbolData.getSymbol())) * 100.0;
                        }
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Change: %d Change percent: %f Symbol: %s", "FINNIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, oi - oiMap.get(symbolData.getSymbol()), changePercent, symbolData.getSymbol());

                        if (Math.abs(changePercent) >= configs.getOiPercent() && oi > 500000 && LocalDate.now().getDayOfWeek().equals(DayOfWeek.TUESDAY)) {
                            email.append(response);
                            email.append("\n");
                        } else if ((Math.abs(changePercent) >= configs.getOiPercent())) {
                            log.info("{} has change % above oi percent", symbolData.getSymbol());
                        }
                        oiMap.put(symbolData.getSymbol(), oi);
                        log.info("OI Data | {}", response);
                    } else {
                        oiMap.put(symbolData.getSymbol(), oi);
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Symbol: %s", "FINNIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, symbolData.getSymbol());

                        log.info("OI Data | {}", response);
                    }
                }
            } catch (Exception e) {
                log.error("Error in fetching oi of symbol {}", symbolData.getSymbol(), e);
            }
        }
        log.info("Oi Map");
        for (Map.Entry<String, Integer> entry : oiMap.entrySet()) {
            log.info("{} : {}", entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : oiMap.entrySet()) {
            try {
                String symbol = entry.getKey();
                if (symbol.contains("CE")) {
                    String peSymbol = entry.getKey().replace("CE", "PE");
                    if (configs.getOiTradeMap().containsKey(symbol)) {
                        OiTrade oiTrade = configs.getOiTradeMap().get(symbol);
                        int oldCeOi = oiTrade.getCeOi();
                        int oldPeOi = oiTrade.getPeOi();
                        int newCeOi = entry.getValue();
                        int newPeOi = oiMap.get(peSymbol);
                        if (newCeOi == oldCeOi && newPeOi == oldPeOi) {
                            log.info("Old and new ce an pe oi are same for symbol {}", symbol);
                        } else {
                            boolean eligible = oiTrade.isEligible();
                            double diffPercent = ((double) Math.abs(newCeOi - newPeOi) / (double) Math.min(newCeOi, newPeOi));
                            diffPercent = diffPercent * 100.0;

                            //Big cross over without initial diff
                            boolean bigeligible = false;
                            if (oldPeOi > oldCeOi && newCeOi > newPeOi) {
                                bigeligible = true;
                            }
                            if (oldCeOi > oldPeOi && newPeOi > newCeOi) {
                                bigeligible = true;
                            }
                            if (bigeligible) {
                                log.info("Big eligible found true for symbol {}", symbol);
                            }
                            if (eligible || bigeligible) {
                                if (diffPercent >= finalDiff && !configs.getOiBasedTradePlaced()) {
                                    String tradeSymbol = newCeOi > newPeOi ? symbol : peSymbol;
                                    String opt = String.format("Symbol %s has Oi cross. OiDiff: %d. Sell option: %s",
                                            symbol.replace("CE", ""), Math.abs(newCeOi - newPeOi), tradeSymbol);

                                    if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.TUESDAY)) {
                                        // finnifty only
                                        if (symbol.contains("FINNIFTY")) {
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                        }
                                        // skip if of nifty
                                    } else if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY)) {
                                        // nifty only
                                        if (!symbol.contains("FINNIFTY")) { // nifty
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                        }
                                    } else {
                                        log.info(opt);
                                        sendMessage.sendMessage(opt);
                                        placeOrders(tradeSymbol);
                                    }

                                    // set trade placed
                                    // configs.setOiBasedTradePlaced(true); Add code to sell option

                                    // reset
                                    log.info("Reset oi enabled to false after trade placed for {}", tradeSymbol);
                                    configs.getOiTradeMap().put(tradeSymbol, OiTrade.builder().ceOi(newCeOi)
                                            .peOi(newPeOi).eligible(false).build());
                                }
                                if (newCeOi > 0 && newPeOi > 0 && diffPercent < finalDiff) {
                                    if (eligible==true) {
                                        eligible=true;
                                    } else if (diffPercent <= diffInitial) {
                                        eligible = true;
                                    }
                                    log.info("Oi updated for {} with enabled {}", symbol, eligible);
                                    configs.getOiTradeMap().put(symbol, OiTrade.builder().ceOi(newCeOi)
                                            .peOi(newPeOi).eligible(eligible).build());
                                }
                            } else {
                                if (newCeOi > 0 && newPeOi > 0) {
                                    diffPercent = ((double) Math.abs(newCeOi - newPeOi) / (double) Math.min(newCeOi, newPeOi));
                                    diffPercent = diffPercent * 100.0;

                                    if (eligible==true) {
                                        eligible=true;
                                    } else if (diffPercent <= diffInitial) {
                                        eligible = true;
                                    }
                                    configs.getOiTradeMap().put(symbol, OiTrade.builder().ceOi(newCeOi)
                                            .peOi(newPeOi).eligible(eligible).build());
                                }
                            }
                        }
                    } else {
                        int ceOi = entry.getValue();
                        int peOi = oiMap.get(peSymbol);
                        if (ceOi > 0 && peOi > 0) {
                            double diffPercent = ((double) Math.abs(ceOi - peOi) / (double) Math.min(ceOi, peOi));
                            diffPercent = diffPercent * 100.0;

                            boolean eligible = false;
                            if (diffPercent <= diffInitial) {
                                eligible = true;
                            }
                            configs.getOiTradeMap().put(symbol, OiTrade.builder().ceOi(ceOi)
                                    .peOi(peOi).eligible(eligible).build());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error occurred in processing strike: {}", entry.getKey(), e);
            }
        }
        log.info("Oi based trade Map");
        for (Map.Entry<String, OiTrade> entry : configs.getOiTradeMap().entrySet()) {
            log.info("{} : {}", entry.getKey(), entry.getValue());
        }
        log.info("Finished tracking oi based trade");
    }

    private void placeOrders(String tradeSymbol) throws Exception {
        String opt = "";
        if (configs.isOiBasedTradeEnabled()) {
            opt = "Oi based trade enabled. Initiating trade for " + tradeSymbol;
            log.info(opt);
            sendMessage.sendMessage(opt);
            int qty = configs.getOiBasedTradeQty();
            int maxQty = 1800;
            int fullBatches = qty / maxQty;
            int remainingQty = qty % maxQty;
            int i;
            String tradeToken = "";
            Double price = 0.0;

            SymbolData sellSymbolData = fetchSellSymbol(tradeSymbol);
            int strikeDiff;
            if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY) ||
                    LocalDate.now().getDayOfWeek().equals(DayOfWeek.TUESDAY)) {
                strikeDiff = 100;
            } else {
                strikeDiff = 200;
            }
            SymbolData buySymbolData;
            String indexName = tradeSymbol.startsWith("NIFTY") ? "NIFTY" : "FINNIFTY";
            String optionType = tradeSymbol.endsWith("CE") ? "CE" : "PE";
            int buyStrike = optionType.equals("CE") ? (sellSymbolData.getStrike() + strikeDiff) :
                    (sellSymbolData.getStrike() - strikeDiff);
            buySymbolData = configs.getSymbolMap().get(indexName + "_" + buyStrike + "_" + optionType);

            // place full and remaining orders.
            Double buyLtp = getLtp(buySymbolData.getToken());
            for (i = 0; i < fullBatches; i++) {
                Order order = stopAtMaxLossScheduler.placeOrder(buySymbolData.getSymbol(), buySymbolData.getToken(), buyLtp, maxQty, Constants.TRANSACTION_TYPE_BUY, 0.0);
                if (order != null) {
                    opt = String.format("Buy order placed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format("Buy order failed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }
            if (remainingQty>0) {
                Order order = stopAtMaxLossScheduler.placeOrder(buySymbolData.getSymbol(), buySymbolData.getToken(), buyLtp, remainingQty, Constants.TRANSACTION_TYPE_BUY, 0.0);
                if (order != null) {
                    opt = String.format("Buy order placed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format("Buy order failed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }

            // initiate sell orders.
            Double sellLtp = getLtp(sellSymbolData.getToken());
            Order sellOrder;
            if (fullBatches>=1) {
                sellOrder = stopAtMaxLossScheduler.placeOrder(sellSymbolData.getSymbol(), sellSymbolData.getToken(), sellLtp, maxQty, Constants.TRANSACTION_TYPE_SELL, 0.0);
                if (sellOrder != null) {
                    opt = String.format("Sell order placed for %s, qty %d", sellSymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format("Sell order failed for %s, qty %d", sellSymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }
            fullBatches --;
            if (remainingQty>0) {
                fullBatches ++;
            }

            // initiate other sl orders with trigger price
            // update config with trade placed
            Double triggerPriceDiff = 0.0;
            if (sellLtp >= 30.0) {
                triggerPriceDiff = 3.0;
            } else if (sellLtp >= 20.0) {
                triggerPriceDiff = 2.5;
            } else if (sellLtp >= 10) {
                triggerPriceDiff = 2.0;
            } else if (sellLtp >= 5) {
                triggerPriceDiff = 1.2;
            } else {
                triggerPriceDiff = 1.0;
            }
            Double triggerPrice = sellLtp - triggerPriceDiff;
            for (i = 0; i < fullBatches; i++) {
                int sellqty = (i == (fullBatches - 1)) ? remainingQty : maxQty;

                sellOrder = stopAtMaxLossScheduler.placeOrder(sellSymbolData.getSymbol(), sellSymbolData.getToken(), sellLtp, sellqty, Constants.TRANSACTION_TYPE_SELL, triggerPrice);
                if (sellOrder != null) {
                    opt = String.format("Sell order placed for %s, qty %d. Trigger price %f", sellSymbolData.getSymbol(), sellqty, triggerPrice);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format("Sell order failed for %s, qty %d. Trigger price %f", sellSymbolData.getSymbol(), sellqty, triggerPrice);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
                triggerPrice = triggerPrice - triggerPriceDiff;
            }
            configs.setOiBasedTradePlaced(true);
        }
    }

    private SymbolData fetchSellSymbol(String tradeSymbol) {
        try {

            int i;
            String indexName = tradeSymbol.startsWith("NIFTY") ? "NIFTY" : "FINNIFTY";
            String optionType = tradeSymbol.endsWith("CE") ? "CE" : "PE";

            SymbolData symbolData = getSymbolData(tradeSymbol);
            int strike = symbolData.getStrike();
            int step = 50;

            Double ltpLimit;
            if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY) ||
                    LocalDate.now().getDayOfWeek().equals(DayOfWeek.TUESDAY)) {
                ltpLimit = 23.0;
            } else {
                ltpLimit = 40.0;
            }


            for (i = 0; i < 50; i++) {
                Double ltp = getLtp(symbolData.getToken());
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
            log.error("Error fetching sell symbol");
        }
        return null;
    }

    private SymbolData getSymbolData(String symbol) {
        for (SymbolData symbolData : symbolDataList) {
            if (symbol.equals(symbolData.getSymbol())) {
                return symbolData;
            }
        }
        return null;
    }

    public Double roundOff(Double val) {
        return Math.round(val*10.0)/10.0;
    }

    // every 5 mins
    //@Scheduled(fixedDelay = 300000)
    public void TrackOi() {

        if (true) {
            LocalDateTime localDateTime = LocalDateTime.now();
            boolean sendMail = true;
            //if (localDateTime.getDayOfWeek().equals(DayOfWeek.THURSDAY) || localDateTime.getDayOfWeek().equals(DayOfWeek.TUESDAY)) {

            LocalTime localStartTimeMarket = LocalTime.of(9, 41, 0);
            LocalTime localEndTime = LocalTime.of(23, 50, 1);
            LocalTime now1 = LocalTime.now();
            if (!(now1.isAfter(localStartTimeMarket) && now1.isBefore(localEndTime))) {
                return;
            }
            Instant now = Instant.now();
            log.info("Track oi started with oiPercent {}", configs.getOiPercent());

            // fetch index values as NSE segment


            // index ltp
            double niftyLtp = 0;
            double finniftyLtp = 0;
            try {
                niftyLtp = getOi("26000", "NSE");
                finniftyLtp = 0;
                finniftyLtp = finniftyLtp + (getOi("1333", "NSE") * 36.5);
                finniftyLtp = finniftyLtp + (getOi("4963", "NSE") * 20.2);
                finniftyLtp = finniftyLtp + (getOi("4244", "NSE") * 0.74);
                finniftyLtp = finniftyLtp + (getOi("1922", "NSE") * 7.7);
                finniftyLtp = finniftyLtp + (getOi("5900", "NSE") * 8.46);
                finniftyLtp = finniftyLtp + (getOi("3045", "NSE") * 6.8);
                finniftyLtp = finniftyLtp + (getOi("16675", "NSE") * 2.8);
                finniftyLtp = finniftyLtp / 80.0;
                finniftyLtp = configs.getFinniftyValue();
                log.info("Using rough index values nifty: {}, finnifty: {}", niftyLtp, finniftyLtp);

            } catch (InterruptedException e) {
                log.error("Error fetch index ltp", e);
            }
            LocalDate expiryDateNifty = getExpiryDate(DayOfWeek.THURSDAY);
            LocalDate expiryDateFinNifty = getExpiryDate(DayOfWeek.TUESDAY);
            int oi;
            int diff = 500; // index value diff
            int finniftyDiff = 1500;
            StringBuilder email = new StringBuilder();
            for (SymbolData symbolData : symbolDataList) {
                try {
                    if (symbolData.getName().equals("NIFTY") && expiryDateNifty.equals(symbolData.getExpiry()) && Math.abs(symbolData.getStrike() - niftyLtp) <= diff) {
                        oi = getOi(symbolData.getToken(), "NFO");
                        if (oi == -1) {
                            continue;
                        }

                        if (oiMap.containsKey(symbolData.getSymbol())) {
                            double changePercent;
                            if (oiMap.get(symbolData.getSymbol()) == 0) {
                                changePercent = 0;
                            } else {
                                changePercent = ((double) (oi - oiMap.get(symbolData.getSymbol())) / (double) oiMap.get(symbolData.getSymbol())) * 100.0;
                            }
                            String response = String.format("Index: %s, Option: %s, current oi: %d, Change: %d Change percent: %f Symbol: %s", "NIFTY", symbolData.getStrike() + " " +
                                    symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, oi - oiMap.get(symbolData.getSymbol()), changePercent, symbolData.getSymbol());

                            if (Math.abs(changePercent) >= configs.getOiPercent() && oi > 500000 && LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY)) {
                                email.append(response);
                                email.append("\n");
                            } else if ((Math.abs(changePercent) >= configs.getOiPercent())) {
                                log.info("{} has change % above oi percent", symbolData.getSymbol());
                            }
                            oiMap.put(symbolData.getSymbol(), oi);
                            log.info("OI Data | {}", response);
                        } else {
                            oiMap.put(symbolData.getSymbol(), oi);
                            String response = String.format("Index: %s, Option: %s, current oi: %d, Symbol: %s", "NIFTY", symbolData.getStrike() + " " +
                                    symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, symbolData.getSymbol());

                            log.info("OI Data | {}", response);
                        }

                    } else if (symbolData.getName().equals("FINNIFTY") && expiryDateFinNifty.equals(symbolData.getExpiry()) && Math.abs(symbolData.getStrike() - finniftyLtp) <= finniftyDiff) {
                        oi = getOi(symbolData.getToken(), "NFO");

                        if (oi == -1) {
                            continue;
                        }
                        if (oiMap.containsKey(symbolData.getSymbol())) {
                            double changePercent;
                            if (oiMap.get(symbolData.getSymbol()) == 0) {
                                changePercent = 0;
                            } else {
                                changePercent = ((double) (oi - oiMap.get(symbolData.getSymbol())) / (double) oiMap.get(symbolData.getSymbol())) * 100.0;
                            }
                            String response = String.format("Index: %s, Option: %s, current oi: %d, Change: %d Change percent: %f Symbol: %s", "FINNIFTY", symbolData.getStrike() + " " +
                                    symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, oi - oiMap.get(symbolData.getSymbol()), changePercent, symbolData.getSymbol());

                            if (Math.abs(changePercent) >= configs.getOiPercent() && oi > 500000 && LocalDate.now().getDayOfWeek().equals(DayOfWeek.TUESDAY)) {
                                email.append(response);
                                email.append("\n");
                            } else if ((Math.abs(changePercent) >= configs.getOiPercent())) {
                                log.info("{} has change % above oi percent", symbolData.getSymbol());
                            }
                            oiMap.put(symbolData.getSymbol(), oi);
                            log.info("OI Data | {}", response);
                        } else {
                            oiMap.put(symbolData.getSymbol(), oi);
                            String response = String.format("Index: %s, Option: %s, current oi: %d, Symbol: %s", "FINNIFTY", symbolData.getStrike() + " " +
                                    symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, symbolData.getSymbol());

                            log.info("OI Data | {}", response);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error fetching oi after process {}", symbolData.getSymbol(), e);
                }
            }

            if (email.length() > 0) {
                log.info("Sending mail {}", email.toString());
                sendMessage.sendMessage(email.toString());
            }
            log.info("Track oi finished in {} s", Instant.now().getEpochSecond() - now.getEpochSecond());
        } else {
            return;
        }
    }

    private LocalDate getExpiryDate(DayOfWeek day) {
        LocalDate localDate = LocalDate.now();
        while (true) {
            if (localDate.getDayOfWeek().equals(day)) {
                return localDate;
            }
            localDate = localDate.plusDays(1);
        }
    }

    private int getOi(String token, String segment) throws InterruptedException {
        for (int i=0;i<60;i++) {
            try {
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.add("Content-Type", "application/json");
                httpHeaders.add("Accept", "*/*");
                httpHeaders.add("Authorization", "Bearer " + configs.getTokenForMarketData());
                httpHeaders.add("X-UserType", "USER");
                httpHeaders.add("X-SourceID", "WEB");
                httpHeaders.add("X-ClientLocalIP", "122.161.95.166");
                httpHeaders.add("X-ClientPublicIP", "122.161.95.166");
                httpHeaders.add("X-MACAddress", "122.161.95.166");
                httpHeaders.add("X-PrivateKey", configs.getMarketPrivateKey());
                // httpHeaders.add("Connection","keep-alive");
                JSONObject jsonObject = new JSONObject("{\n" +
                        "\"mode\": \"FULL\",\n" +
                        "  \"exchangeTokens\": {\n" +
                        "    \"" + segment + "\": [\n" +
                        "      \"" + token + "\"\n" +
                        "    ]\n" +
                        "  }\n" +
                        "}");
                HttpEntity<String> httpEntity = new HttpEntity<>(jsonObject.toString(), httpHeaders);

                ResponseEntity<String> responseEntity = restTemplate.exchange(marketDataUrl, HttpMethod.POST, httpEntity, String.class);
                //log.info("oi {}", responseEntity.toString());
                int startIndex = responseEntity.toString().indexOf("{");
                int endEndex = responseEntity.toString().lastIndexOf("}");

                JSONObject op = new JSONObject(responseEntity.toString().substring(startIndex, endEndex + 1));
                if (op.optString("message").equals("SUCCESS")) {
                    JSONObject data = op.optJSONObject("data");
                    JSONArray fetched = data.optJSONArray("fetched");
                    JSONObject oiData = fetched.optJSONObject(0);
                    Thread.sleep(20);
                    if (segment.equals("NFO")) {
                        return oiData.optInt("opnInterest");
                    } else {
                        return (int) oiData.optDouble("ltp");
                    }
                } else {
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                Thread.sleep(20);
                //return -1;
            }
        }
        log.error("Error fetching oi for token {}", token);
        return -1;
    }

    private double getLtp(String token) throws InterruptedException {
        String segment = "NFO";
        for (int i = 0; i < 60; i++) {
            try {
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.add("Content-Type", "application/json");
                httpHeaders.add("Accept", "*/*");
                httpHeaders.add("Authorization", "Bearer " + configs.getTokenForMarketData());
                httpHeaders.add("X-UserType", "USER");
                httpHeaders.add("X-SourceID", "WEB");
                httpHeaders.add("X-ClientLocalIP", "122.161.95.166");
                httpHeaders.add("X-ClientPublicIP", "122.161.95.166");
                httpHeaders.add("X-MACAddress", "122.161.95.166");
                httpHeaders.add("X-PrivateKey", configs.getMarketPrivateKey());
                // httpHeaders.add("Connection","keep-alive");
                JSONObject jsonObject = new JSONObject("{\n" +
                        "\"mode\": \"FULL\",\n" +
                        "  \"exchangeTokens\": {\n" +
                        "    \"" + segment + "\": [\n" +
                        "      \"" + token + "\"\n" +
                        "    ]\n" +
                        "  }\n" +
                        "}");
                HttpEntity<String> httpEntity = new HttpEntity<>(jsonObject.toString(), httpHeaders);

                ResponseEntity<String> responseEntity = restTemplate.exchange(marketDataUrl, HttpMethod.POST, httpEntity, String.class);
                //log.info("oi {}", responseEntity.toString());
                int startIndex = responseEntity.toString().indexOf("{");
                int endEndex = responseEntity.toString().lastIndexOf("}");

                JSONObject op = new JSONObject(responseEntity.toString().substring(startIndex, endEndex + 1));
                if (op.optString("message").equals("SUCCESS")) {
                    JSONObject data = op.optJSONObject("data");
                    JSONArray fetched = data.optJSONArray("fetched");
                    JSONObject oiData = fetched.optJSONObject(0);
                    Thread.sleep(20);

                    return oiData.optDouble("ltp");
                } else {
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                Thread.sleep(20);
                //return -1;
            }
        }
        log.error("Error fetching oi for token {}", token);
        return -1;
    }
}

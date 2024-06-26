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
    double finalDiff = 12.0;

    // Price for multiple sell trades
    double p1Percent = 20.0; // of initial price
    double p2Percent = 30.0; // of initial price
    double p3 = 20.0;

    int q1Percent = 50;

    int q2Percent = 30;

    // loss percents
    double loss2 = 76.0;
    double loss1 = 38.0;

    double pointSl = 10.0; // if option move opposite these points then at max loss1 happens if 2nd qty not sold
    // if 2nd qty also sold then loss2 may happen

    // testing
    int ceCount = 0;

    int peCount = 0;

    // Due to volatility keep disable midcp nifty cross over trade
    boolean isMidcpNiftyOiCrossTradeEnabled = true; // after 2.30 pm only

    boolean isSensexOiCrossTradeEnabled = false; // after 2.30 pm only

    boolean isNiftyOiCrossTradeEnabled = true;

    private String SENSEX = "SENSEX";

    private String BSE_NFO = "BFO";
    private String NSE_NFO = "NFO";

    private boolean isNiftyExpiry = false;
    private boolean isBankNiftyExpiry = false;
    private boolean isFinNiftyExpiry = false;
    private boolean isMidcapNiftyExpiry = false;

    private boolean isSensexExpiry = false;

    private boolean isOiCrossTradeAllowed = false;

    // @Scheduled(cron = "0 50 8 * * ?")
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

        log.info(com.smartapi.Constants.IMP_LOG+"Initializing rest template");
        restTemplate = new RestTemplateBuilder().setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        log.info(com.smartapi.Constants.IMP_LOG+"Rest template initialized");
        log.info(com.smartapi.Constants.IMP_LOG+"Fetching symbols");
        HttpEntity<String> httpEntity = new HttpEntity<String>("ip");
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
                    HttpMethod.GET, httpEntity, String.class);
            success = 1;

        } catch (Exception e) {
            response = restTemplate.exchange("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
                    HttpMethod.GET, httpEntity, String.class);
            log.error(com.smartapi.Constants.IMP_LOG+"Failed fetching symbols, retrying");
            success = 0;
        }

        log.info(com.smartapi.Constants.IMP_LOG+"Fetched symbols");

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
                            .expiry(getLocalDate(ob.getString("expiry")))
                            .expiryString(ob.getString("expiry"))
                            .strike(((int) Double.parseDouble(ob.optString("strike"))) / 100)
                            .build();
                    cnt++;
                    if (symbolData.getSymbol()!=null && symbolData.getSymbol().startsWith("NIFTY")) {
                        isNiftyExpiry = true;
                    }
                    if (symbolData.getSymbol()!=null && symbolData.getSymbol().startsWith("FINNIFTY")) {
                        isFinNiftyExpiry = true;
                    }
                    if (symbolData.getSymbol()!=null && symbolData.getSymbol().startsWith("MIDCPNIFTY")) {
                        isMidcapNiftyExpiry = true;
                    }
                    if (symbolData.getSymbol()!=null && symbolData.getSymbol().startsWith("BANKNIFTY")) {
                        isBankNiftyExpiry = true;
                    }
                    if (Arrays.asList("NIFTY", "FINNIFTY", "MIDCPNIFTY", "BANKNIFTY", SENSEX).contains(symbolData.getName())
                            && ("NFO".equals(ob.optString("exch_seg")) || BSE_NFO.equals(ob.optString("exch_seg")))) {
                        symbolDataList.add(symbolData);
                        matchedExpiries++;

                    }
                }
            }
            /*symbolDataList.sort(new Comparator<SymbolData>() {
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
            });*/

            symbolDataList.add(SymbolData.builder().expiryString("03NOV2023").symbol("smbl").strike(1500).name("symb").token("tkn").build());
            if (configs.isOiBasedTradeAllowed()) {
                log.info(com.smartapi.Constants.IMP_LOG+"Processed {} symbols. Oi change percent {}. Matched Expiries {}, Non match expiries {} for today", symbolDataList.size(), configs.getOiPercent(),
                        matchedExpiries, jsonArray.length() - matchedExpiries);
                log.info(com.smartapi.Constants.IMP_LOG+"Expiry today Nifty {}, Finnifty {}, midcap {}, bankNifty {}", isNiftyExpiry,
                        isFinNiftyExpiry, isMidcapNiftyExpiry, isBankNiftyExpiry);
            }
            jsonArray = null;
        } catch (Exception e) {
            log.error(com.smartapi.Constants.IMP_LOG + "Error in processing symbols at count {}, {}", cnt, e.getMessage());
        }
        if (success == 1) {
            configs.setSymbolDataList(symbolDataList);
        }
        /*for (SymbolData symbolData : symbolDataList) {
            log.info("Symbol {}, expiry {}", symbolData.getSymbol(), symbolData.getExpiryString());
        }*/
        return success;
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

    //@Scheduled(cron = "0 * * * * *")
    public void tradeOnBasisOfOi() throws Exception {
        /*
        if total ce oi surpass total pe oi for some specific strike, then initiate a trade. sold option whose oi is larger after surpass
        incident found on today, when 19600 pe oi surpassed 19600 ce oi and 19600 pe became 0 from 12 to 0.
        similarly for 19650 strike.
         */
        if (configs.getSymbolDataList() == null || configs.getSymbolDataList().isEmpty()) {
            log.info(com.smartapi.Constants.IMP_LOG+"Loading symbols");
            init();
            log.info(com.smartapi.Constants.IMP_LOG+"Loaded symbols");
        }
        if (!configs.isOiBasedTradeAllowed()) {
            log.info("Oi based trade not allowed as per configs, skipping");
            return;
        }
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMMyyyy"));
        today = today.substring(0,5) + today.substring(7);
        today = today.toUpperCase();
        try {
            log.info("Configs used currently for oi based trade.oiPercent: {}\n oiBasedTradeEnabled: {}\n oiBasedTradePlaced {}\n midcapQty {}\n " +
                            "Nifty qty {}\n Finnifty Qty {}\n Banknifty qty {}\n SEXSX Qty {}\n Today {}\n maxLoss Limit {}\n symbolsLoaded {}\n isOiCrossTradeAllowed {}\n",
                    configs.getOiPercent(),
                    configs.isOiBasedTradeEnabled(),
                    configs.getOiBasedTradePlaced(),
                    configs.getOiBasedTradeMidcapQty(),
                    configs.getOiBasedTradeQtyNifty(),
                    configs.getOiBasedTradeQtyFinNifty(),
                    configs.getOiBasedTradeBankNiftyQty(),
                    configs.getOiBasedTradeSensexQty(),
                    today, configs.getMaxLossAmount(),
                    configs.getSymbolDataList().size(),
                    isOiCrossTradeAllowed);
            } catch (Exception exception) {
        }
        // Any change made to from and to time here, should also be made in stop loss scheduler
        // Time now is not allowed.
        LocalTime localStartTimeMarket = LocalTime.of(11, 50, 0);
        LocalTime localEndTime = LocalTime.of(20, 20, 1);
        LocalTime now1 = LocalTime.now();

        LocalDate expiryDateNifty = getExpiryDate(DayOfWeek.THURSDAY); // use wednesday if holiday on exp
        LocalDate expiryDateFinNifty = getExpiryDate(DayOfWeek.TUESDAY); // if holiday then skip its monday exp as midcap there
        LocalDate expiryDateMidcapNifty = getExpiryDate(DayOfWeek.MONDAY); // use friday exp
        LocalDate expiryDateBankNifty = getExpiryDate(DayOfWeek.WEDNESDAY);
        LocalDate expirySenSex = getExpiryDate(DayOfWeek.FRIDAY);

        double niftyLtp = 0;
        double finniftyLtp = 0;
        double midcapLtp = 0;
        double bankNiftyLtp = 0;
        double sensxLtp = 0;
        try {
            niftyLtp = getOi("99926000", "NSE");
            bankNiftyLtp = getOi("99926009", "NSE");

            finniftyLtp = getOi("99926037", "NSE");
            midcapLtp = getOi("99926074", "NSE");
            sensxLtp = getOi("99919000", "BSE");
            if (midcapLtp == -1) {
                midcapLtp = configs.getMidcapNiftyValue();
            }
            log.info("Index values nifty: {}\n finnifty: {}\n midcapnifty {}\n BankNifty {}\n Sensx {}\n  Nifty exp {}\n fnnifty exp {}\n midcap exp {}\n BankNifty exp {}\n, sensx exp {}",
                    niftyLtp, finniftyLtp, midcapLtp, bankNiftyLtp, sensxLtp, expiryDateNifty, expiryDateFinNifty, expiryDateMidcapNifty, expiryDateBankNifty, expirySenSex);
            if (configs.getTradedOptions()!=null && !configs.getTradedOptions().isEmpty()) {
                log.info("Traded options\n");
                for (String str : configs.getTradedOptions()) {
                    log.info(str);
                }
            }

        } catch (InterruptedException e) {
            log.error("Error fetch index ltp", e);
        }
        if (!(now1.isAfter(localStartTimeMarket) && now1.isBefore(localEndTime))) {
            return;
        }

        int oi;
        int niftyDiff = 500; // index value diff
        int finniftyDiff = 500;
        int midcapDiff = 500;
        int bankNiftyDiff = 800;
        int senSxDiff = 1000;
        StringBuilder email = new StringBuilder();
        List<SymbolData> symbols = configs.getSymbolDataList();
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

                        if (Math.abs(changePercent) >= configs.getOiPercent() && oi > 500000 && symbolData.getSymbol().contains(today)) {
                            email.append(response);
                            email.append("\n\n");
                        } else if ((Math.abs(changePercent) >= configs.getOiPercent())) {
                            log.info(com.smartapi.Constants.IMP_LOG+"{} has change % above oi percent. percent {}\n", symbolData.getSymbol(), changePercent);
                        }
                        oiMap.put(symbolData.getSymbol(), oi);
                        //log.info("OI Data | {}", response);
                    } else {
                        oiMap.put(symbolData.getSymbol(), oi);
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Symbol: %s", "NIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, symbolData.getSymbol());

                        //log.info("OI Data | {}", response);
                    }

                }
                if (symbolData.getName().equals("FINNIFTY") && Math.abs(symbolData.getStrike() - finniftyLtp) <= finniftyDiff) {
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

                        if (Math.abs(changePercent) >= configs.getOiPercent() && oi > 500000 && symbolData.getSymbol().contains(today)) {
                            email.append(response);
                            email.append("\n\n");
                        } else if ((Math.abs(changePercent) >= configs.getOiPercent())) {
                            log.info(com.smartapi.Constants.IMP_LOG+"{} has change % above oi percent. Percent {}\n", symbolData.getSymbol(), changePercent);
                        }
                        oiMap.put(symbolData.getSymbol(), oi);
                        //log.info("OI Data | {}", response);
                    } else {
                        oiMap.put(symbolData.getSymbol(), oi);
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Symbol: %s", "FINNIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, symbolData.getSymbol());

                        //log.info("OI Data | {}", response);
                    }
                }
                if (symbolData.getName().equals("MIDCPNIFTY") && Math.abs(symbolData.getStrike() - midcapLtp) <= midcapDiff) {
                    String name = "";
                    name = name + "MIDCPNIFTY_";
                    name = name + symbolData.getStrike() + "_" + (symbolData.getSymbol().endsWith("CE") ? "CE" : "PE");
                    if (!configs.getSymbolMap().containsKey(name)) {
                        configs.getSymbolMap().put(name, symbolData);
                    }
                    oi = getOi(symbolData.getToken(), "NFO");

                    if (oi == -1) {
                        continue;
                    }

                    /*if (symbolData.getSymbol().equals("MIDCPNIFTY13NOV239175CE")) {
                        oi = getOiTestData(490650);
                    }*/
                    if (oiMap.containsKey(symbolData.getSymbol())) {
                        double changePercent;
                        if (oiMap.get(symbolData.getSymbol()) == 0) {
                            changePercent = 0;
                        } else {
                            changePercent = ((double) (oi - oiMap.get(symbolData.getSymbol())) / (double) oiMap.get(symbolData.getSymbol())) * 100.0;
                        }
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Change: %d Change percent: %f Symbol: %s", "MIDCPNIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, oi - oiMap.get(symbolData.getSymbol()), changePercent, symbolData.getSymbol());

                        if (Math.abs(changePercent) >= configs.getOiPercent() && oi > 500000 && symbolData.getSymbol().contains(today)) {
                            email.append(response);
                            email.append("\n\n");
                        } else if ((Math.abs(changePercent) >= configs.getOiPercent())) {
                            log.info(com.smartapi.Constants.IMP_LOG+"{} has change % above oi percent. percent {}\n", symbolData.getSymbol(), changePercent);
                        }
                        oiMap.put(symbolData.getSymbol(), oi);
                        //log.info("OI Data | {}", response);
                    } else {
                        oiMap.put(symbolData.getSymbol(), oi);
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Symbol: %s", "NIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, symbolData.getSymbol());

                        //log.info("OI Data | {}", response);
                    }
                }
                if (symbolData.getName().equals("BANKNIFTY") && Math.abs(symbolData.getStrike() - bankNiftyLtp) <= bankNiftyDiff) {
                    String name = "";
                    name = name + "BANKNIFTY_";
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
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Change: %d Change percent: %f Symbol: %s", "BANKNIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, oi - oiMap.get(symbolData.getSymbol()), changePercent, symbolData.getSymbol());

                        if (Math.abs(changePercent) >= configs.getOiPercent() && oi > 500000 && symbolData.getSymbol().contains(today)) {
                            email.append(response);
                            email.append("\n\n");
                        } else if ((Math.abs(changePercent) >= configs.getOiPercent())) {
                            log.info(com.smartapi.Constants.IMP_LOG+"{} has change % above oi percent. percent {}\n", symbolData.getSymbol(), changePercent);
                        }
                        oiMap.put(symbolData.getSymbol(), oi);
                        //log.info("OI Data | {}", response);
                    } else {
                        oiMap.put(symbolData.getSymbol(), oi);
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Symbol: %s", "BANKNIFTY", symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, symbolData.getSymbol());

                        //log.info("OI Data | {}", response);
                    }

                }
                if (symbolData.getName().equals(SENSEX) && Math.abs(symbolData.getStrike() - sensxLtp) <= senSxDiff) {
                    String name = "";
                    name = name + SENSEX + "_";
                    name = name + symbolData.getStrike() + "_" + (symbolData.getSymbol().endsWith("CE") ? "CE" : "PE");
                    if (!configs.getSymbolMap().containsKey(name)) {
                        configs.getSymbolMap().put(name, symbolData);
                    }
                    if (!configs.getSensxSymbolData().containsKey(symbolData.getSymbol())) {
                        configs.getSensxSymbolData().put(symbolData.getSymbol(), symbolData);
                    }

                    oi = getOi(symbolData.getToken(), BSE_NFO);

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
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Change: %d Change percent: %f Symbol: %s", SENSEX, symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, oi - oiMap.get(symbolData.getSymbol()), changePercent, symbolData.getSymbol());

                        if (Math.abs(changePercent) >= configs.getOiPercent() && oi > 500000 && symbolData.getSymbol().contains(today)) {
                            email.append(response);
                            email.append("\n\n");
                        } else if ((Math.abs(changePercent) >= configs.getOiPercent())) {
                            log.info(com.smartapi.Constants.IMP_LOG+"{} has change % above oi percent. percent {}\n", symbolData.getSymbol(), changePercent);
                        }
                        oiMap.put(symbolData.getSymbol(), oi);
                        //log.info("OI Data | {}", response);
                    } else {
                        oiMap.put(symbolData.getSymbol(), oi);
                        String response = String.format("Index: %s, Option: %s, current oi: %d, Symbol: %s", SENSEX, symbolData.getStrike() + " " +
                                symbolData.getSymbol().substring(symbolData.getSymbol().length() - 2), oi, symbolData.getSymbol());

                        //log.info("OI Data | {}", response);
                    }
                }
            } catch (Exception e) {
                log.error(com.smartapi.Constants.IMP_LOG+"Error in fetching oi of symbol {}", symbolData.getSymbol(), e);
            }
        }
        /*log.info("Oi Map:");
        for (Map.Entry<String, Integer> entry : oiMap.entrySet()) {
            log.info("{} : {}", entry.getKey(), entry.getValue());
        }*/
        if (LocalTime.now().isAfter(LocalTime.of(14, 10)) && !email.toString().isEmpty()) {
            sendMessage.sendMessage(email.toString());
        }
        boolean traded = false;
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
                            //log.info("Old and new ce and pe oi are same for symbol {}", symbol);
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
                                log.info(com.smartapi.Constants.IMP_LOG+"Big eligible found true for symbol {}\n", symbol);
                            }
                            if (eligible || bigeligible) {
                                if (diffPercent >= finalDiff) {
                                    String tradeSymbol = newCeOi > newPeOi ? symbol : peSymbol;
                                    String opt = String.format("Symbol %s has Oi cross. OiDiff: %d. Sell option: %s",
                                            symbol.replace("CE", ""), Math.abs(newCeOi - newPeOi), tradeSymbol);

                                    // set trade placed
                                    // configs.setOiBasedTradePlaced(true); Add code to sell option

                                    // reset
                                    log.info(com.smartapi.Constants.IMP_LOG+"Reset oi enabled to false after trade placed for ce/pe of {}\n", tradeSymbol);
                                    configs.getOiTradeMap().put(symbol, OiTrade.builder().ceOi(newCeOi)
                                            .peOi(newPeOi).eligible(false).build());
                                    LocalTime bankNiftyCutoffTime = LocalTime.of(11, 30);
                                    if (isNiftyExpiry && isBankNiftyExpiry) {
                                        bankNiftyCutoffTime = LocalTime.of(13, 30);
                                    }
                                    if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.TUESDAY) && isOiCrossTradeAllowed) {
                                        // finnifty only
                                        if (symbol.contains("FINNIFTY")) {
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);

                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        } else if (symbol.contains(today) && symbol.startsWith("BANKNIFTY") && LocalTime.now().isAfter(bankNiftyCutoffTime)) { // BANKNIFTY
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        }
                                    } else if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY) && isOiCrossTradeAllowed) {
                                        if (symbol.startsWith("NIFTY") && isNiftyOiCrossTradeEnabled
                                                && LocalTime.now().isAfter(LocalTime.of(13, 30)) && LocalTime.now().isBefore(LocalTime.of(15, 15))) { // nifty
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        } else if (symbol.contains(today) && symbol.startsWith("BANKNIFTY") && LocalTime.now().isAfter(bankNiftyCutoffTime)) { // BANKNIFTY
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        }
                                    } else if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.MONDAY) && isOiCrossTradeAllowed) {
                                        // nifty only
                                        if (symbol.startsWith("MIDCPNIFTY") && isMidcpNiftyOiCrossTradeEnabled
                                                && LocalTime.now().isAfter(LocalTime.of(14, 30))
                                                && LocalTime.now().isBefore(LocalTime.of(15, 20))) { // MIDCPNIFTY
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        } else if (symbol.contains(today) && symbol.contains("FINNIFTY")) {
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);

                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        }
                                    } else if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.WEDNESDAY) && isOiCrossTradeAllowed) {
                                        // check banknifty first and then nifty
                                        if (symbol.contains(today) && symbol.startsWith("BANKNIFTY") && LocalTime.now().isAfter(bankNiftyCutoffTime)) { // BANKNIFTY
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        } else if (symbol.contains(today) && symbol.startsWith("NIFTY") && isNiftyOiCrossTradeEnabled
                                            && LocalTime.now().isAfter(LocalTime.of(13, 30)) && LocalTime.now().isBefore(LocalTime.of(15, 15))) { // NIFTY
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        }
                                    } else if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.FRIDAY) && isOiCrossTradeAllowed) {
                                        // nifty only
                                        if (symbol.contains(today) && symbol.startsWith("MIDCPNIFTY") && isMidcpNiftyOiCrossTradeEnabled
                                                && LocalTime.now().isAfter(LocalTime.of(14, 30))
                                                && LocalTime.now().isBefore(LocalTime.of(15, 20))) { // MIDCPNIFTY
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        } else if (symbol.startsWith(SENSEX) && isSensexOiCrossTradeEnabled
                                                && LocalTime.now().isAfter(LocalTime.of(14, 30))
                                                && LocalTime.now().isBefore(LocalTime.of(15, 20))) { // SENSX
                                            log.info(opt);
                                            sendMessage.sendMessage(opt);
                                            placeOrders(tradeSymbol);
                                            traded = true;
                                        }
                                    }
                                }
                                if (newCeOi > 0 && newPeOi > 0 && diffPercent < finalDiff) {
                                    if (eligible) {
                                        eligible=true;
                                    } else if (diffPercent <= diffInitial) {
                                        eligible = true;
                                    }
                                    if (eligible) {
                                        log.info(com.smartapi.Constants.IMP_LOG+"Oi updated for {} with enabled {}\n", symbol, eligible);
                                    }
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
                            log.info(com.smartapi.Constants.IMP_LOG+"Symbol: {} stored with initial CE OI: {}, PE OI: {}\n", symbol, ceOi, peOi);
                            configs.getOiTradeMap().put(symbol, OiTrade.builder().ceOi(ceOi)
                                    .peOi(peOi).eligible(eligible).build());
                        }
                    }
                }
            } catch (Exception e) {
                log.error(com.smartapi.Constants.IMP_LOG+"Error occurred in processing strike: {}\n", entry.getKey(), e);
            }
        }

        if (!traded) {
            trackMaxOiMail(today, midcapLtp, finniftyLtp, bankNiftyLtp, niftyLtp, sensxLtp);
        } else {
            log.info("Skipping max oi diff trade as already placed oi cross trade");
        }

        log.info("Oi based trade Map\n");
        printOiMap(today);
        System.gc();
    }

    private void printOiMap(String today) {
        String today_20 = today.substring(0, 5) + "20" + today.substring(5);
        try {
            List<String> result = new ArrayList<>();
            int j;
            for (Map.Entry<String, OiTrade> entry : configs.getOiTradeMap().entrySet()) {
                if (entry.getKey().startsWith("NIFTY")) {
                    result.add(entry.getKey());
                }
            }
            result.sort(String::compareTo);
            for (j = 0; j < result.size(); j++) {
                log.info("{} : {}\n", result.get(j), configs.getOiTradeMap().get(result.get(j)));
            }
            result.clear();

            for (Map.Entry<String, OiTrade> entry : configs.getOiTradeMap().entrySet()) {
                if (entry.getKey().startsWith("FINNIFTY")) {
                    result.add(entry.getKey());
                }
            }
            result.sort(String::compareTo);
            for (j = 0; j < result.size(); j++) {
                log.info("{} : {}\n", result.get(j), configs.getOiTradeMap().get(result.get(j)));
            }
            result.clear();

            for (Map.Entry<String, OiTrade> entry : configs.getOiTradeMap().entrySet()) {
                if (entry.getKey().startsWith("MIDCP")) {
                    result.add(entry.getKey());
                }
            }
            result.sort(String::compareTo);
            for (j = 0; j < result.size(); j++) {
                log.info("{} : {}\n", result.get(j), configs.getOiTradeMap().get(result.get(j)));
            }
            result.clear();

            for (Map.Entry<String, OiTrade> entry : configs.getOiTradeMap().entrySet()) {
                if (entry.getKey().startsWith("BANKNIFTY")) {
                    result.add(entry.getKey());
                }
            }
            result.sort(String::compareTo);
            for (j = 0; j < result.size(); j++) {
                log.info("{} : {}\n", result.get(j), configs.getOiTradeMap().get(result.get(j)));
            }

            result.clear();
            for (Map.Entry<String, OiTrade> entry : configs.getOiTradeMap().entrySet()) {
                if (entry.getKey().startsWith(SENSEX)) {
                    result.add(entry.getKey());
                }
            }
            result.sort(String::compareTo);
            for (j = 0; j < result.size(); j++) {
                log.info("{} : {}\n", result.get(j), configs.getOiTradeMap().get(result.get(j)));
            }
            result.clear();
        } catch (Exception e) {
            log.error("Error in oi map print ", e);
        }
    }

    private void trackMaxOiMail(String today,
                                double midcapLtp,
                                double finniftyLtp,
                                double bankNiftyLtp,
                                double niftyLtp,
                                double sensxLtp) throws Exception {
        if (configs.isMaxOiBasedTradePlaced()) {
            log.info("Max oi based trade already placed, Returning from max oi based trade");
            return;
        }

        // If there is no oi based trade yet then send top 4 max oi strikes on expiry in descending order
        // after 14:35 (This is to done if there is no oi cross over found)
        LocalTime now = LocalTime.now();
        int maxOi1 = 0;
        int maxOi2 = 0;
        String symbol1 = "";
        String symbol2 = "";
        LocalTime cutoffTime;
        if (isNiftyExpiry && isBankNiftyExpiry) {
            cutoffTime = LocalTime.of(14, 50);
        } else if (isNiftyExpiry) {
            cutoffTime = LocalTime.of(14, 50);
        } else if (isMidcapNiftyExpiry) {
            cutoffTime = LocalTime.of(14, 40);
        } else {
            cutoffTime = LocalTime.of(14, 28);
        }

        if (now.isAfter(cutoffTime)) {
            log.info("Going to init Oi diff trade");
            for (Map.Entry<String, OiTrade> entry : configs.getOiTradeMap().entrySet()) {
                String senSxToday = "";
                if (entry.getKey().startsWith(SENSEX)) {
                    senSxToday = configs.getSensxSymbolData().get(entry.getKey()).getExpiryString();
                    senSxToday = senSxToday.substring(0, 5) + senSxToday.substring(7);
                }

                if (entry.getKey().contains(today) || ((!senSxToday.isEmpty()) && senSxToday.contains(today))) { // expiry
                    int oi = entry.getValue().getCeOi() + entry.getValue().getPeOi();
                    if (oi > maxOi1 && oi > maxOi2) {
                        maxOi2 = maxOi1;
                        maxOi1 = oi;

                        symbol2 = symbol1;
                        symbol1 = entry.getKey();
                    } else if (oi > maxOi2 && oi < maxOi1) {
                        maxOi2 = oi;
                        symbol2 = entry.getKey();
                    } else {

                    }
                }
            }

            String index = getIndexName(symbol1);
            if (index.isEmpty()) {
                log.info("Empty index returning");
                return;
            }

            double indexPrice = 0;
            if (index.startsWith("NIFTY")) {
                indexPrice = niftyLtp;
            } else if (index.startsWith("FINNIFTY")) {
                indexPrice = finniftyLtp;
            } else if (index.startsWith("MIDCPNIFTY")) {
                indexPrice = midcapLtp;
            } else if (index.startsWith("BANKNIFTY")) {
                indexPrice = bankNiftyLtp;
            } else if (index.startsWith(SENSEX)) {
                indexPrice = sensxLtp;
            }
            log.info(com.smartapi.Constants.IMP_LOG+"Index used: {}. Index price: {}", index, indexPrice);

            symbol2 = "";
            symbol1 = "";

            int min1;
            int min2;
            min1 = min2 = 9999999;

            for (Map.Entry<String, OiTrade> entry : configs.getOiTradeMap().entrySet()) {
                String senSxToday = "";
                if (entry.getKey().startsWith(SENSEX)) {
                    senSxToday = configs.getSensxSymbolData().get(entry.getKey()).getExpiryString();
                    senSxToday = senSxToday.substring(0, 5) + senSxToday.substring(7);
                }

                if (entry.getKey().contains(today) || ((!senSxToday.isEmpty()) && senSxToday.contains(today))) { // expiry
                    int diff = (int) Math.abs(indexPrice - configs.getSymbolToStrikeMap().get(entry.getKey()));

                    if (diff < min1 && diff < min2) {
                        min2 = min1;
                        min1 = diff;

                        symbol2 = symbol1;
                        symbol1 = entry.getKey();
                    } else if (diff > min1 && diff < min2) {
                        min2 = diff;
                        symbol2 = entry.getKey();
                    } else if (diff == min1) {
                        min2 = diff;
                        symbol2 = entry.getKey();
                    } else {

                    }
                }
            }

            String symbol = "";
            int diff1 = Math.abs(configs.getOiTradeMap().get(symbol1).getCeOi() - configs.getOiTradeMap().get(symbol1).getPeOi());
            int diff2 = Math.abs(configs.getOiTradeMap().get(symbol2).getCeOi() - configs.getOiTradeMap().get(symbol2).getPeOi());

            if (diff2 > diff1) {
                symbol = symbol2;
            } else {
                symbol = symbol1;
            }

            log.info(com.smartapi.Constants.IMP_LOG+"Symbols. S1: {}, S2: {}, diff1: {}, diff2: {}", symbol1, symbol2, diff1, diff2);

            if (!symbol.isEmpty() && !symbol.contains(SENSEX)) {
                String sellSymbol = configs.getOiTradeMap().get(symbol).getCeOi() > configs.getOiTradeMap().get(symbol).getPeOi()
                        ? symbol : symbol.replace("CE", "PE");
                String op = String.format(com.smartapi.Constants.IMP_LOG+"Max oi based trade is being initiated for symbol %s. Oi diff: %d", sellSymbol, Math.max(diff2, diff1));
                log.info(op);
                sendMessage.sendMessage(op);
                
                if (isMultiSubTrade(sellSymbol)) {
                    placeOrders(sellSymbol);
                } else {
                    placeOrdersForMaxOi(sellSymbol);
                }
                configs.setMaxOiBasedTradePlaced(true);
            }
        } else {
            log.info("Oi diff trade is not allowed at this time. Cutoff: {} and now is {}", cutoffTime, now);
        }

        /**
         * if (now.isAfter(LocalTime.of(14, 31)) && now.isBefore(LocalTime.of(15, 20)) &&
         *                 !configs.getOiBasedTradePlaced()) {
         *             List<OptionData> optionDataList = new ArrayList<>();
         *             for (Map.Entry<String, Integer> entry : oiMap.entrySet()) {
         *                 if (entry.getValue() > 0 && entry.getKey().contains(today)) {
         *                     optionDataList.add(OptionData.builder().symbol(entry.getKey()).oi(entry.getValue()).build());
         *                 }
         *             }
         *             List<OptionData> sortedOptData = optionDataList.stream().sorted((o1, o2) -> (o1.getOi() > o2.getOi() ? -1 : 1)).collect(Collectors.toList());
         *             if (now.getMinute() % 5 == 0 && sortedOptData.size() >= 4) {
         *                 StringBuilder emailContent = new StringBuilder();
         *                 emailContent.append("Symbol, Oi\n");
         *
         *                 emailContent.append(getOiContent(sortedOptData.get(0), today));
         *                 emailContent.append(getOiContent(sortedOptData.get(1), today));
         *                 emailContent.append(getOiContent(sortedOptData.get(2), today));
         *                 emailContent.append(getOiContent(sortedOptData.get(3), today));
         *
         *                 log.info("Max oi data: {}\n", emailContent.toString());
         *                 sendMessage.sendMessage(emailContent.toString());
         *             }
         *         }
         */
    }

    private boolean isMultiSubTrade(String sellSymbol) {
        try {
            String indexName = getIndexName(sellSymbol);
            SymbolData sellSymbolData = fetchSellSymbol(sellSymbol);
            Double sellLtp = getLtp(sellSymbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO : NSE_NFO);
            if (indexName.equals("MIDCPNIFTY")) {
                return sellLtp >= 2.5 ? true : false;
            } else if (indexName.equals("NIFTY")) {
                return sellLtp >= 5.0 ? true : false;
            } else if (indexName.equals("BANKNIFTY")) {
                return sellLtp >= 9.0 ? true : false;
            } else if (indexName.equals(SENSEX)) {
                return sellLtp >= 13.0 ? true : false;
            } else { // finnifty
                return sellLtp >= 5.0 ? true : false;
            }
        } catch (Exception e) {
            log.error("Error in checking multi re trade ", e);

        }
        return true;
    }

    private String getOiContent(OptionData optionData, String today) {
        int todayindex = optionData.getSymbol().indexOf(today);
        return optionData.getSymbol().substring(0,todayindex) + " " + optionData.getSymbol()
                .substring(todayindex+7) + " : " + optionData.getOi() + "\n";
    }

    private int getOiTestData(int oi) {
        if (ceCount == 0) {
            ceCount++;
            return oi;
        } else if (ceCount == 1) {
            ceCount++;
            return 529275;
        } else {
            ceCount++;
            return 435435343;
        }
    }

    private boolean isExpiry() {
        if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY) ||
                LocalDate.now().getDayOfWeek().equals(DayOfWeek.TUESDAY)) {
            return true;
        }
        return false;
    }

    private boolean isNewTradeAllowed(String sellSymbol) {
        int mtm = configs.getMtm();
        boolean isAnotherTradeAllowed = false;
        boolean isBwAllowedTime = LocalTime.now().isBefore(LocalTime.of(15, 20));
        if (isBwAllowedTime && (mtm >= 0 || (mtm < 0 && Math.abs(mtm) < configs.getMaxLossAmount()))) {
            log.info("MTM: {}. Initiating another oi cross trade after closing previous trade", mtm);
            sendMessage.sendMessage("MTM: " + mtm + " . Initiating another oi cross trade after closing previous trade");

            isAnotherTradeAllowed = true;
        } else {
            log.info("Can not initiate re oi cross over / max oi trade");
            sendMessage.sendMessage("Can not initiate re oi cross over / max oi trade");
        }

        // check if new trade is more profitable

        try {
            String indexName = getIndexName(sellSymbol);
            SymbolData sellSymbolData = fetchSellSymbol(sellSymbol);
            Double sellLtp = getLtp(sellSymbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO : NSE_NFO);
            if (sellLtp - configs.getSoldOptionLtp() >= 2.0) {
                log.info("New sold pos has higher price. Old pos ltp: {}, New pos ltp: {}", configs.getSoldOptionLtp(), sellLtp);
            } else {
                log.info("New sold pos has lower price, skipping retrade");
                isAnotherTradeAllowed = false;
                sendMessage.sendMessage("New sold pos has lower price for symbol " + sellSymbolData.getSymbol() + " skipping retrade");
            }
        } catch (Exception e) {
            log.error("Error in fetching old pos data");
            sendMessage.sendMessage("Error in fetching old pos data");
        }

        if (isAnotherTradeAllowed) {
            try {
                stopAtMaxLossScheduler.stopOnMaxLossProcess(true);
                log.info(com.smartapi.Constants.IMP_LOG+"Closed old pos for new re trade");
                sendMessage.sendMessage("Closed old pos for new re trade");

            } catch (Exception e) {
                log.error(com.smartapi.Constants.IMP_LOG+"Error closing all pos for retrade ", e);
                sendMessage.sendMessage("Error closing all pos for retrade");
            }
        }
        return isAnotherTradeAllowed;
    }

    public void placeOrders(String tradeSymbol) throws Exception {
        String opt = "";

        boolean isAnotherTradeAllowed = isNewTradeAllowed(tradeSymbol);
        if ((configs.isOiBasedTradeEnabled() && !configs.getOiBasedTradePlaced())
                || isAnotherTradeAllowed) {

            int qty;
            String indexName = getIndexName(tradeSymbol);
            int maxQty = 500;
            /**
             * Make max qty changes in stop at max loss scheduler also
             */

            if (indexName.equals("MIDCPNIFTY")) {
                maxQty = 4200;
            } else if (indexName.equals("BANKNIFTY")) {
                maxQty = 900;
            } else if (indexName.equals(SENSEX)) {
                maxQty = 1000;
            } else {
                maxQty = 1800;
            }

            int i;

            SymbolData sellSymbolData = fetchSellSymbol(tradeSymbol);
            opt = com.smartapi.Constants.IMP_LOG+"Oi based trade enabled. Initiating trade for " + sellSymbolData.getSymbol();
            log.info(opt);
            sendMessage.sendMessage(opt);
            int strikeDiff;

            strikeDiff = 100; // used for buy order
            if (indexName.equals("MIDCPNIFTY")) {
                strikeDiff = 50;
            } else if (indexName.equals("BANKNIFTY")) {
                strikeDiff = 300;
            } else if (indexName.equals(SENSEX)) {
                strikeDiff = 300;
            } else {
                strikeDiff = 100;
            }

            if (indexName.equals("MIDCPNIFTY")) {
                qty = configs.getOiBasedTradeMidcapQty();
            } else if (indexName.equals("NIFTY")) {
                qty = configs.getOiBasedTradeQtyNifty();
            } else if (indexName.equals("BANKNIFTY")) {
                qty = configs.getOiBasedTradeBankNiftyQty();
            } else if (indexName.equals(SENSEX)) {
                qty = configs.getOiBasedTradeSensexQty();
            } else {
                qty = configs.getOiBasedTradeQtyFinNifty();
            }

            /*} else {
                strikeDiff = 200;
                p1 = 10.0;
                p2 = 10.0;
                qty = configs.getOiBasedTradeQtyNonExp();
            }*/
            double q1, q2, q3;
            double maxLoss = configs.getMaxLossAmount();
            Double sellLtp = getLtp(sellSymbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO: NSE_NFO);
            q1 = (q1Percent / 100.0) * qty;
            q2 = (q2Percent / 100.0) * qty;

            double lotSize;
            if (indexName.equals("MIDCPNIFTY")) {
                lotSize = configs.getMidcapNiftyLotSize();
            } else if (indexName.equals("NIFTY")) {
                lotSize = configs.getNiftyLotSize();
            } else if (indexName.equals("BANKNIFTY")) {
                lotSize = configs.getBankNiftyLotSize();
            } else if (indexName.equals(SENSEX)) {
                lotSize = configs.getSensexLotSize();
            } else {
                lotSize = configs.getFinniftyLotSize();
            }
            log.info(com.smartapi.Constants.IMP_LOG+"Max Qty {}. Index {}. Tradable Qty {}, LotSize {}\n", maxQty, indexName, qty, lotSize);

            int fullBatches = qty / maxQty;
            int remainingQty = qty % maxQty;
            remainingQty = (remainingQty % (int) lotSize == 0) ? remainingQty : remainingQty - (remainingQty % (int) lotSize);
            log.info(com.smartapi.Constants.IMP_LOG+"Trade Details: strikediff {}, price1 {}%, price2 {}%, price3 {}%, total qty {}\n", strikeDiff, p1Percent, p2Percent, p3, qty);
            SymbolData buySymbolData;

            String optionType = tradeSymbol.endsWith("CE") ? "CE" : "PE";
            int buyStrike = optionType.equals("CE") ? (sellSymbolData.getStrike() + strikeDiff) :
                    (sellSymbolData.getStrike() - strikeDiff);
            buySymbolData = configs.getSymbolMap().get(indexName + "_" + buyStrike + "_" + optionType);

            List<String> tradedOptions = configs.getTradedOptions();
            tradedOptions.add(sellSymbolData.getSymbol());
            tradedOptions.add(buySymbolData.getSymbol());
            configs.setTradedOptions(tradedOptions);

            // place full and remaining orders.
            Double buyLtp = getLtp(buySymbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO: NSE_NFO);
            for (i = 0; i < fullBatches; i++) {
                Order order = stopAtMaxLossScheduler.placeOrder(buySymbolData.getSymbol(), buySymbolData.getToken(), buyLtp, maxQty, Constants.TRANSACTION_TYPE_BUY, 0.0);
                if (order != null) {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Buy order placed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Buy order failed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }
            if (remainingQty > 0) {
                Order order = stopAtMaxLossScheduler.placeOrder(buySymbolData.getSymbol(), buySymbolData.getToken(), buyLtp, remainingQty, Constants.TRANSACTION_TYPE_BUY, 0.0);
                if (order != null) {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Buy order placed for %s, qty %d", buySymbolData.getSymbol(), remainingQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Buy order failed for %s, qty %d", buySymbolData.getSymbol(), remainingQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }

            // initiate sell orders.
            q1 = q1 / lotSize;
            q2 = q2 / lotSize;
            //log.info("Q1 {}, Q2 {}", q1, q2);
            Double trg1 = sellLtp - (sellLtp * p1Percent) / 100.0;
            Double trg2 = sellLtp - (sellLtp * p2Percent) / 100.0;
            Double trg3 = trg2 - (trg2 * p3) / 100.0;

            q3 = qty - q1 - q2;
            q3 = q3 / lotSize;

            int intq1, intq2, intq3, intq4;
            intq1 = (int) q1 * (int) lotSize;
            intq2 = (int) q2 * (int) lotSize;
            intq3 = qty - intq1 - intq2;

            intq4 = qty - intq1 - intq2 - intq3;
            if (intq1>qty) {
                log.info(com.smartapi.Constants.IMP_LOG+"Adjusting Q1");
                intq1 = qty;
                intq2 = 0;
                intq3 = 0;
                intq4 = 0;
            } else if (intq1+intq2>qty) {
                log.info(com.smartapi.Constants.IMP_LOG+"Adjusting Qty2");
                intq2 = qty - intq1;

                intq3 = 0;
                intq4 = 0;
            } else if (intq1+intq2+intq3>qty) {
                log.info(com.smartapi.Constants.IMP_LOG+"Adjusting Qty3");
                intq3 = qty - intq1 - intq2;

                intq4 = 0;
            } else if (intq1+intq2+intq3+intq4>qty) {
                log.info(com.smartapi.Constants.IMP_LOG+"Adjusting Qty4");
                intq4 = qty - intq1 - intq2 - intq3;
            }
            List<Integer> qtys1 = getQtyList(intq1, maxQty, lotSize);
            List<Integer> qtys2 = getQtyList(intq2, maxQty, lotSize);
            List<Integer> qtys3 = getQtyList(intq3, maxQty, lotSize);
            List<Integer> qtys4 = getQtyList(intq4, maxQty, lotSize);

            log.info(com.smartapi.Constants.IMP_LOG+"Trade Details: Q1 {}, Q2 {}, Q3 {}, Q4 {} p1 {}, p2 {}, p3 {}, p4 {}\n",
                    intq1, intq2, intq3, intq4, sellLtp, trg1, trg2, trg3);

            Order sellOrder;

            for (Integer qt : qtys1) {
                sellOrder = stopAtMaxLossScheduler.placeOrder(sellSymbolData.getSymbol(), sellSymbolData.getToken(), sellLtp, qt, Constants.TRANSACTION_TYPE_SELL, 0.0);
                if (sellOrder != null) {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order placed for %s, qty %d, Price %f", sellSymbolData.getSymbol(), qt, sellLtp);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order failed for %s, qty %d Price %f", sellSymbolData.getSymbol(), qt, sellLtp);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }

            for (Integer qt : qtys2) {
                sellOrder = stopAtMaxLossScheduler.placeOrder(sellSymbolData.getSymbol(), sellSymbolData.getToken(), sellLtp, qt, Constants.TRANSACTION_TYPE_SELL, trg1);
                if (sellOrder != null) {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order placed for %s, qty %d. Trigger price %f", sellSymbolData.getSymbol(), qt, trg1);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order failed for %s, qty %d. Trigger price %f", sellSymbolData.getSymbol(), qt, trg1);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }

            for (Integer qt : qtys3) {
                sellOrder = stopAtMaxLossScheduler.placeOrder(sellSymbolData.getSymbol(), sellSymbolData.getToken(), sellLtp, qt, Constants.TRANSACTION_TYPE_SELL, trg2);
                if (sellOrder != null) {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order placed for %s, qty %d. Trigger price %f", sellSymbolData.getSymbol(), qt, trg2);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order failed for %s, qty %d. Trigger price %f", sellSymbolData.getSymbol(), qt, trg2);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }

            configs.setOiBasedTradePlaced(true);
        } else {
            log.info(com.smartapi.Constants.IMP_LOG+"Trade found but oi based trade not enabled or trade already placed. Check if manual trade required. Sell {}\n", tradeSymbol);
            sendMessage.sendMessage("Trade found but oi based trade not enabled or trade already placed. Check if manual trade required. Sell " + tradeSymbol);
            sendMessage.sendMessage("Sell symbol " + fetchSellSymbol(tradeSymbol));
        }
    }

    private double getQ2Abs(double maxLoss, Double sellLtp) {

        return (((loss2 / 100.0) * maxLoss) - ((loss1 / 100.0) * maxLoss)) / (pointSl + ((sellLtp * p1Percent) / 100.0));
    }

    private double getQ1Abs(double maxLoss) {

        return (loss1 / 1000.0) * maxLoss;
    }

    private double getQ3Abs(double maxLoss, Double sellLtp, Double trg1, Double trg2) {
        double q2 = (((loss2 / 100.0) * maxLoss) - ((loss1 / 100.0) * maxLoss)) / (pointSl + ((sellLtp * p1Percent) / 100.0));
        double q1 = (loss1 / 1000.0) * maxLoss;
        double p = trg2 + pointSl + 4.0;

        return (maxLoss - (p * q1) + (q1 * sellLtp) - (p * q2) + (q2 * trg1)) / (p - trg2);
    }

    private List<Integer> getQtyList(int qty, int maxQty, double lotSize) {

        int i;
        int fullBatches = qty/maxQty;
        int partialQty = qty%maxQty;
        List<Integer> qtys = new ArrayList<>();
        if (qty<0) {
            log.info(com.smartapi.Constants.IMP_LOG+"Negative qty, use 0 qty");
            qtys.add(0);
            return qtys;
        }

        for (i=0;i<fullBatches;i++) {
            qtys.add(maxQty);
        }
        if (partialQty>0) {
            int part = (int) ((partialQty * 1.0) / lotSize);
            part = part * (int) lotSize;
            qtys.add(part);
        }
        log.info(com.smartapi.Constants.IMP_LOG+"Qty list for qty {}: {}", qty, qtys);
        return qtys;
    }

    private String getIndexName(String tradeSymbol) {
        String indexName = "";

        if (tradeSymbol.startsWith("NIFTY")) {
            indexName = "NIFTY";
        } else if (tradeSymbol.startsWith("FINNIFTY")) {
            indexName = "FINNIFTY";
        } else if (tradeSymbol.startsWith("MIDCPNIFTY")) {
            indexName = "MIDCPNIFTY";
        } else if (tradeSymbol.startsWith("BANKNIFTY")) {
            indexName = "BANKNIFTY";
        } else if (tradeSymbol.startsWith(SENSEX)) {
            indexName = SENSEX;
        }
        return indexName;
    }

    private SymbolData fetchSellSymbol(String tradeSymbol) {
        try {
            int i;

            String indexName = getIndexName(tradeSymbol);
            String optionType = tradeSymbol.endsWith("CE") ? "CE" : "PE";

            SymbolData symbolData = getSymbolData(tradeSymbol);
            int strike = symbolData.getStrike();
            int step = 0;
            if (indexName.equals("MIDCPNIFTY")) {
                step = 25;
            } else if (indexName.equals("BANKNIFTY")) {
                step = 100;
            } else if (indexName.equals(SENSEX)) {
                step = 100;
            } else {
                step = 50;
            }
            log.info("Step {}", step);

            Double ltpLimit;
            if (indexName.equals("MIDCPNIFTY")) {
                ltpLimit = 5.0;
            } else if (indexName.equals("BANKNIFTY")) {
                ltpLimit = 20.0;
            } else if (indexName.equals(SENSEX)) {
                ltpLimit = 50.0;
            } else {
                ltpLimit = 9.0;
            }
            log.info("Ltp limit {}", ltpLimit);

            for (i = 0; i < 50; i++) {
                Double ltp = getLtp(symbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO: NSE_NFO);
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

    public void placeOrdersForMaxOi(String tradeSymbol) throws Exception {
        String opt = "";
        boolean isAnotherTradeAllowed = isNewTradeAllowed(tradeSymbol);
        if ((configs.isOiBasedTradeEnabled() && !configs.getOiBasedTradePlaced())
                || isAnotherTradeAllowed) {

            int qty;
            String indexName = getIndexName(tradeSymbol);
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

            int i;
            String tradeToken = "";
            Double price = 0.0;

            SymbolData sellSymbolData = fetchSellSymbol(tradeSymbol);
            opt = com.smartapi.Constants.IMP_LOG+"Oi based trade enabled. Initiating trade for " + sellSymbolData.getSymbol();
            log.info(opt);
            sendMessage.sendMessage(opt);
            int strikeDiff;

            strikeDiff = 100; // used for buy order
            if (indexName.equals("MIDCPNIFTY")) {
                strikeDiff = 50;
            } else if (indexName.equals("BANKNIFTY")) {
                strikeDiff = 300;
            } else if (indexName.equals(SENSEX)) {
                strikeDiff = 300;
            }else {
                strikeDiff = 100;
            }

            if (indexName.equals("MIDCPNIFTY")) {
                qty = configs.getOiBasedTradeMidcapQty();
            } else if (indexName.equals("NIFTY")) {
                qty = configs.getOiBasedTradeQtyNifty();
            } else if (indexName.equals("BANKNIFTY")) {
                qty = configs.getOiBasedTradeBankNiftyQty();
            } else if (indexName.equals(SENSEX)) {
                qty = configs.getOiBasedTradeSensexQty();
            } else {
                qty = configs.getOiBasedTradeQtyFinNifty();
            }

            /*} else {
                strikeDiff = 200;
                p1 = 10.0;
                p2 = 10.0;
                qty = configs.getOiBasedTradeQtyNonExp();
            }*/
            double maxLoss = configs.getMaxLossAmount();
            Double sellLtp = getLtp(sellSymbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO: NSE_NFO);

            double lotSize;
            if (indexName.equals("MIDCPNIFTY")) {
                lotSize = configs.getMidcapNiftyLotSize();
            } else if (indexName.equals("NIFTY")) {
                lotSize = configs.getNiftyLotSize();
            } else if (indexName.equals("BANKNIFTY")) {
                lotSize = configs.getBankNiftyLotSize();
            } else if (indexName.equals(SENSEX)) {
                lotSize = configs.getSensexLotSize();
            } else {
                lotSize = configs.getFinniftyLotSize();
            }
            log.info(com.smartapi.Constants.IMP_LOG+"Max Qty {}. Index {}. Tradable Qty {}, LotSize {}", maxQty, indexName, qty, lotSize);

            int fullBatches = qty / maxQty;
            int remainingQty = qty % maxQty;
            remainingQty = (remainingQty % (int) lotSize == 0) ? remainingQty : remainingQty - (remainingQty % (int) lotSize);
            log.info(com.smartapi.Constants.IMP_LOG+"Trade Details: strikediff {}, price1 {}%, price2 {}%, total qty {}", strikeDiff, p1Percent, p2Percent, qty);
            SymbolData buySymbolData;

            String optionType = tradeSymbol.endsWith("CE") ? "CE" : "PE";
            int buyStrike = optionType.equals("CE") ? (sellSymbolData.getStrike() + strikeDiff) :
                    (sellSymbolData.getStrike() - strikeDiff);
            buySymbolData = configs.getSymbolMap().get(indexName + "_" + buyStrike + "_" + optionType);

            List<String> tradedOptions = configs.getTradedOptions();
            tradedOptions.add(sellSymbolData.getSymbol());
            tradedOptions.add(buySymbolData.getSymbol());
            configs.setTradedOptions(tradedOptions);

            // place full and remaining orders.
            Double buyLtp = getLtp(buySymbolData.getToken(), indexName.equals(SENSEX) ? BSE_NFO: NSE_NFO);
            for (i = 0; i < fullBatches; i++) {
                Order order = stopAtMaxLossScheduler.placeOrder(buySymbolData.getSymbol(), buySymbolData.getToken(), buyLtp, maxQty, Constants.TRANSACTION_TYPE_BUY, 0.0);
                if (order != null) {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Buy order placed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Buy order failed for %s, qty %d", buySymbolData.getSymbol(), maxQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }
            if (remainingQty > 0) {
                Order order = stopAtMaxLossScheduler.placeOrder(buySymbolData.getSymbol(), buySymbolData.getToken(), buyLtp, remainingQty, Constants.TRANSACTION_TYPE_BUY, 0.0);
                if (order != null) {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Buy order placed for %s, qty %d", buySymbolData.getSymbol(), remainingQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Buy order failed for %s, qty %d", buySymbolData.getSymbol(), remainingQty);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }

            // initiate sell orders.
            Order sellOrder;
            for (i = 0; i < fullBatches; i++) {
                sellOrder = stopAtMaxLossScheduler.placeOrder(sellSymbolData.getSymbol(), sellSymbolData.getToken(), sellLtp, maxQty, Constants.TRANSACTION_TYPE_SELL, 0.0);
                if (sellOrder != null) {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order placed for %s, qty %d, Price %f", sellSymbolData.getSymbol(), maxQty, sellLtp);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order failed for %s, qty %d Price %f", sellSymbolData.getSymbol(), maxQty, sellLtp);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }
            if (remainingQty > 0) {
                sellOrder = stopAtMaxLossScheduler.placeOrder(sellSymbolData.getSymbol(), sellSymbolData.getToken(), sellLtp, remainingQty, Constants.TRANSACTION_TYPE_SELL, 0.0);
                if (sellOrder != null) {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order placed for %s, qty %d, Price %f", sellSymbolData.getSymbol(), remainingQty, sellLtp);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                } else {
                    opt = String.format(com.smartapi.Constants.IMP_LOG+"Sell order failed for %s, qty %d Price %f", sellSymbolData.getSymbol(), remainingQty, sellLtp);
                    log.info(opt);
                    sendMessage.sendMessage(opt);
                }
            }
            configs.setOiBasedTradePlaced(true);
        } else {
            log.info(com.smartapi.Constants.IMP_LOG+"Trade found but oi based trade not enabled or trade already placed. Check if manual trade required. Sell {}", tradeSymbol);
            sendMessage.sendMessage("Trade found but oi based trade not enabled or trade already placed. Check if manual trade required. Sell " + tradeSymbol);
            sendMessage.sendMessage("Sell symbol " + fetchSellSymbol(tradeSymbol));
        }
    }

    private SymbolData getSymbolData(String symbol) {
        for (SymbolData symbolData : configs.getSymbolDataList()) {
            if (symbol.equals(symbolData.getSymbol())) {
                return symbolData;
            }
        }
        return null;
    }

    public Double roundOff(Double val) {
        return Math.round(val*10.0)/10.0;
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
        for (int i=0;i<100;i++) {
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
                    Thread.sleep(15);
                    if (segment.equals("NFO") || segment.equals(BSE_NFO)) {
                        return oiData.optInt("opnInterest");
                    } else {
                        return (int) oiData.optDouble("ltp");
                    }
                } else {
                    Thread.sleep(15);
                }
            } catch (Exception e) {
                Thread.sleep(15);
                //return -1;
            }
        }
        log.error("Error fetching oi for token {}", token);
        return -1;
    }

    private double getLtp(String token, String segment) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
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
                    Thread.sleep(10);

                    return oiData.optDouble("ltp");
                } else {
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                Thread.sleep(10);
                //return -1;
            }
        }
        log.error("Error fetching oi for token {}", token);
        return -1;
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
            for (SymbolData symbolData : configs.getSymbolDataList()) {
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
                                email.append("\n\n");
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
                                email.append("\n\n");
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
}

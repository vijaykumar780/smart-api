package com.smartapi.service;

import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.utils.Constants;
import com.smartapi.Configs;
import com.smartapi.pojo.OptionData;
import com.smartapi.pojo.SymbolData;
import lombok.extern.log4j.Log4j2;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Log4j2
@Service
public class IronMan {

    String NIFTY = "NIFTY";
    String FINNIFTY = "FINNIFTY";
    String MIDCPNIFTY = "MIDCPNIFTY";
    String BANKNIFTY = "BANKNIFTY";

    private RestTemplate restTemplate;

    @Autowired
    private SendMessage sendMessage;

    @Autowired
    private StopAtMaxLossScheduler stopAtMaxLossScheduler;

    private List<SymbolData> symbolDataList;

    @Autowired
    private Configs configs;

    private Map<String, Integer> oiMap;

    private List<SymbolData> symbolLtpData;

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

            content.append("Total max orders allowed: " + configs.getTotalMaxOrdersAllowed() + "\n");
            content.append("Traded Options: " + configs.getTradedOptions() + "\n");

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
        symbolLtpData = new ArrayList<>();

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
        int startIndex = response.toString().indexOf("[");
        int endINdex = response.toString().indexOf(",[Server");
        int cnt = 0;
        try {
            JSONArray jsonArray = new JSONArray(response.toString().substring(startIndex, endINdex));

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

                    if (symbolData.getSymbol() != null && symbolData.getSymbol().startsWith(NIFTY)) {
                        isNiftyExpiry = true;
                    }
                    if (symbolData.getSymbol() != null && symbolData.getSymbol().startsWith(FINNIFTY)) {
                        isFinNiftyExpiry = true;
                    }
                    if (symbolData.getSymbol() != null && symbolData.getSymbol().startsWith(MIDCPNIFTY)) {
                        isMidcapNiftyExpiry = true;
                    }
                    if (symbolData.getSymbol() != null && symbolData.getSymbol().startsWith(BANKNIFTY)) {
                        isBankNiftyExpiry = true;
                    }
                    if (Arrays.asList(NIFTY, FINNIFTY, MIDCPNIFTY, BANKNIFTY, SENSEX).contains(symbolData.getName())
                            && ("NFO".equals(ob.optString("exch_seg")) || BSE_NFO.equals(ob.optString("exch_seg")))) {
                        symbolDataList.add(symbolData);
                        matchedExpiries++;

                    }
                }
            }

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

    @Scheduled(cron = "0 * * * * *")
    public void ironMan() throws Exception {
        /*
            sell call and pe both at 10.10 having price below threshold
            use sl on both options
            close hedge if its option is sl
            close pos at 3.28 pm
         */
        if (configs.getSymbolDataList() == null || configs.getSymbolDataList().isEmpty()) {
            log.info(com.smartapi.Constants.IMP_LOG + "Loading symbols");
            init();
            log.info(com.smartapi.Constants.IMP_LOG + "Loaded symbols");
        }

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMMyyyy"));
        today = today.substring(0, 5) + today.substring(7);
        today = today.toUpperCase();
        try {
            log.info("Configs used iron man:\n " +
                            "nifty threshold price{}\n " +
                            "finnifty threshold price{}\n " +
                            "midcap threshold price{}\n " +
                            "Banknifty threshold price{}\n " +
                            "Today {}\n " +
                            "symbolsLoaded {}\n ",
                    configs.getNiftyThresholdPrice(),
                    configs.getFinniftyThresholdPrice(),
                    configs.getMidcapNiftyThresholdPrice(),
                    configs.getBankniftyThresholdPrice(),
                    today,
                    configs.getSymbolDataList().size());
        } catch (Exception exception) {

        }
        // Any change made to from and to time here, should also be made in stop loss scheduler
        // Time now is not allowed.
        LocalTime localStartTimeMarket = LocalTime.of(9, 30, 0);
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
            if (configs.getTradedOptions() != null && !configs.getTradedOptions().isEmpty()) {
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

        if (configs.isIronManTradePlaced()) {
            log.info("Iron man trade already placed, skipping");
        }

        int oi;
        int niftyDiff = 800; // index value diff
        int finniftyDiff = 800;
        int midcapDiff = 500;
        int bankNiftyDiff = 1000;
        int senSxDiff = 1000;
        StringBuilder email = new StringBuilder();
        List<SymbolData> symbols = configs.getSymbolDataList();
        for (SymbolData symbolData : symbols) {
            if (!configs.getSymbolToStrikeMap().containsKey(symbolData.getSymbol())) {
                configs.getSymbolToStrikeMap().put(symbolData.getSymbol(), symbolData.getStrike());
            }

            try {
                if (symbolData.getName().equals(NIFTY) && Math.abs(symbolData.getStrike() - niftyLtp) <= niftyDiff) {
                    symbolLtpData.add(SymbolData.builder()
                            .ltp(getLtp(symbolData.getToken(), NSE_NFO))
                            .token(symbolData.getToken())
                            .expiry(symbolData.getExpiry())
                            .name(symbolData.getName())
                            .strike(symbolData.getStrike())
                            .symbol(symbolData.getSymbol())
                            .expiryString(symbolData.getExpiryString())
                            .build());
                }
                if (symbolData.getName().equals(FINNIFTY) && Math.abs(symbolData.getStrike() - finniftyLtp) <= finniftyDiff) {
                    symbolLtpData.add(SymbolData.builder()
                            .ltp(getLtp(symbolData.getToken(), NSE_NFO))
                            .token(symbolData.getToken())
                            .expiry(symbolData.getExpiry())
                            .name(symbolData.getName())
                            .strike(symbolData.getStrike())
                            .symbol(symbolData.getSymbol())
                            .expiryString(symbolData.getExpiryString())
                            .build());
                }
                if (symbolData.getName().equals(MIDCPNIFTY) && Math.abs(symbolData.getStrike() - midcapLtp) <= midcapDiff) {
                    symbolLtpData.add(SymbolData.builder()
                            .ltp(getLtp(symbolData.getToken(), NSE_NFO))
                            .token(symbolData.getToken())
                            .expiry(symbolData.getExpiry())
                            .name(symbolData.getName())
                            .strike(symbolData.getStrike())
                            .symbol(symbolData.getSymbol())
                            .expiryString(symbolData.getExpiryString())
                            .build());
                }
                if (symbolData.getName().equals(BANKNIFTY) && Math.abs(symbolData.getStrike() - bankNiftyLtp) <= bankNiftyDiff) {
                    symbolLtpData.add(SymbolData.builder()
                            .ltp(getLtp(symbolData.getToken(), NSE_NFO))
                            .token(symbolData.getToken())
                            .expiry(symbolData.getExpiry())
                            .name(symbolData.getName())
                            .strike(symbolData.getStrike())
                            .symbol(symbolData.getSymbol())
                            .expiryString(symbolData.getExpiryString())
                            .build());
                }
                if (symbolData.getName().equals(SENSEX) && Math.abs(symbolData.getStrike() - sensxLtp) <= senSxDiff) {

                }
            } catch (Exception e) {
                log.error(com.smartapi.Constants.IMP_LOG + "Error in fetching oi of symbol {}", symbolData.getSymbol(), e);
            }
        }

        log.info("Prepared symbol data with ltp");
        String INDEX = "";
        double thresholdPrice = 0;

        int strikeDiff;

        strikeDiff = 100; // used for buy order

        if (isNiftyExpiry) {
            INDEX = NIFTY;
            thresholdPrice = configs.getNiftyThresholdPrice();
        } else if (isFinNiftyExpiry) {
            INDEX = FINNIFTY;
            thresholdPrice = configs.getFinniftyThresholdPrice();
        } else if (isMidcapNiftyExpiry) {
            INDEX = MIDCPNIFTY;
            strikeDiff = 50;
            thresholdPrice = configs.getMidcapNiftyThresholdPrice();
        } else if (isBankNiftyExpiry) {
            INDEX = BANKNIFTY;
            strikeDiff = 300;
            thresholdPrice = configs.getBankniftyThresholdPrice();
        }

        log.info("Index to trade: {}, strike diff: {}, threshold price: {}", INDEX, strikeDiff, thresholdPrice);
        if (LocalTime.now().isAfter(LocalTime.of(10, 10))) {
            SymbolData ceOption = null;
            SymbolData ceHedge = null;
            double ceMaxLtp = 0.0;

            SymbolData peOption = null;
            SymbolData peHedge = null;

            double peMaxLtp = 0.0;
            for (SymbolData symbolData : symbolLtpData) {
                if (symbolData.getSymbol().startsWith(INDEX) && symbolData.getSymbol().endsWith("CE")
                        && symbolData.getLtp() <= thresholdPrice && ceMaxLtp < symbolData.getLtp()) {
                    ceOption = symbolData;
                    ceMaxLtp = symbolData.getLtp();
                }
            }
            for (SymbolData symbolData : symbolLtpData) {
                if (symbolData.getSymbol().startsWith(INDEX) && symbolData.getSymbol().endsWith("PE")
                        && symbolData.getLtp() <= thresholdPrice && peMaxLtp < symbolData.getLtp()) {
                    peOption = symbolData;
                    peMaxLtp = symbolData.getLtp();
                }
            }
            if (ceOption == null || peOption == null) {
                log.error("Either ce or pe option is null");
                return;
            }

            int ceBuyStrike = ceOption.getStrike() + strikeDiff;
            int peBuyStrike = peOption.getStrike() - strikeDiff;
            ceHedge = filterSymbol(symbolDataList, INDEX, "CE", ceBuyStrike);
            peHedge = filterSymbol(symbolDataList, INDEX, "PE", peBuyStrike);

            if (ceHedge == null || peHedge == null) {
                log.error("Either ce or pe hedge is null");
                return;
            }
            log.info("CE option 1: {}, CE opion 2: {}, PE option 1: {}, PE option 2: {}", ceOption.getSymbol(),
                    ceHedge.getSymbol(), peOption.getSymbol(), peHedge.getSymbol());

        } else if (LocalTime.now().isAfter(LocalTime.of(15, 28)) &&
                LocalTime.now().isBefore(LocalTime.of(15, 30))) {
            log.info("Closing all pos");
            stopAtMaxLossScheduler.stopOnMaxLoss();
            log.info("Closed all pos");
        }
        System.gc();
    }

    private SymbolData filterSymbol(List<SymbolData> symbolDataList, String indexName, String type, int strike) {
        for (SymbolData symbolData : symbolDataList) {
            if (symbolData.getSymbol().startsWith(indexName) && symbolData.getSymbol().endsWith(type)
                    && symbolData.getStrike()==strike) {
                return symbolData;
            }
        }
        return null;
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
}

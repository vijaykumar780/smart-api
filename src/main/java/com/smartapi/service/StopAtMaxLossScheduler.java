package com.smartapi.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.models.OrderParams;
import com.angelbroking.smartapi.models.User;
import com.angelbroking.smartapi.utils.Constants;
import com.smartapi.Configs;
import com.smartapi.pojo.OiTrade;
import com.smartapi.pojo.OrderData;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import lombok.extern.log4j.Log4j2;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Log4j2
@Service
public class StopAtMaxLossScheduler {

    private static final String SENSEX = "SENSEX";
    //@Autowired
    SmartConnect historySmartConnect;

    @Autowired
    SendMessage sendMessage;

    //@Autowired
    SmartConnect tradingSmartConnect;

    //@Autowired
    SmartConnect marketSmartConnect;

    @Value("${maxLossAmount}")
    Double maxLossAmount;

    @Autowired
    Configs configs;

    double initialSlQtyPercent = 30.0;
    Double reTradeTriggerPricePercent = 35.0;

    double rTorOiTrade = 1.0; // less risk for oi cross trade

    double rTorMaxOiTrade = 1.3; // more risk for Max oi

    @PostConstruct
    public void init() {
        boolean initCt = false;

        String totp;// = configs.getTotps().get(0);
        //configs.getTotps().remove(0);
        GoogleAuthenticator gAuth = new GoogleAuthenticator();
        totp = String.valueOf(gAuth.getTotpPassword(configs.getTotp()));

        log.info(com.smartapi.Constants.IMP_LOG+"Initiating smart connects. Using totp {}", totp);
        for (int i = 0; i <= 5; i++) {
            try {
                log.info(com.smartapi.Constants.IMP_LOG+"Re-initiating session");
                this.tradingSmartConnect = TradingSmartConnect(totp);
                this.marketSmartConnect = MarketSmartConnect(totp);
                this.historySmartConnect = historySmartConnect(totp);
                configs.setTokenForMarketData(marketSmartConnect.getAccessToken());
                initCt = true;
                break;
            } catch (Exception | SmartAPIException e) {
                log.error(com.smartapi.Constants.IMP_LOG+"Error in re-init session, Retrying ", e);
            }
        }
        if (initCt == false) {
            sendMessage.sendMessage("Failed to re init session");
            log.info(com.smartapi.Constants.IMP_LOG+"Reinited failed");
        } else {
            log.info(com.smartapi.Constants.IMP_LOG+"Reinited success");
        }
    }

    @Scheduled(fixedDelay = 4000)
    public void stopOnMaxLoss() throws Exception {
        memoryAlarmChecker();
        stopOnMaxLossProcess(false);
    }

    private void memoryAlarmChecker() {
        if (LocalTime.now().getSecond() >= 50) {
            String s;
            Process p;
            try {
                p = Runtime.getRuntime().exec("free -h");
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String memoryLine = "";
                int c = 0;
                String mems[];
                int memoryUsed = 0;
                int memCnt = 0;
                int totalMemory = 0;
                while ((s = br.readLine()) != null) {
                    c++;
                    //log.info("Mem {}", s);
                    if (c == 2) {
                        memoryLine = s;
                        mems = memoryLine.split(" ");

                        for (String op : mems) {
                            if (!op.isEmpty()) {
                                memCnt++;
                                if (memCnt == 2) {
                                    totalMemory = Integer.parseInt(op.substring(0, op.length() - 2));
                                } else if (memCnt == 3) {
                                    memoryUsed = Integer.parseInt(op.substring(0, op.length() - 2));
                                }
                            }
                        }
                    }
                }
                p.waitFor();
                p.destroy();
                log.info("Memory remaining: {} MB. Total memory: {}", totalMemory - memoryUsed, totalMemory);
                configs.setRemainingMemory(totalMemory - memoryUsed);
            } catch (Exception e) {
            }
        }
    }

    public void stopOnMaxLossProcess(boolean exitALLFlag) throws Exception {
        LocalTime localStartTimeMarket = LocalTime.of(9,15,0);
        LocalTime localEndTime = LocalTime.of(21,0,5);
        LocalTime localEndTimeMarket = LocalTime.of(15,30,1);
        LocalTime now = LocalTime.now();
        if (!(now.isAfter(localStartTimeMarket) && now.isBefore(localEndTime))) {
            log.info("Current time {} is beyond range {} to {}. Threshold: {} [As per exp / non exp]\n Max Orders remaining {}\n", now, localStartTimeMarket,
                    localEndTime, maxLossAmount, configs.getTotalMaxOrdersAllowed());
            return;
        }
        if (historySmartConnect == null || tradingSmartConnect == null || marketSmartConnect ==null) {
            init();
        }
        JSONObject jsonObject = historySmartConnect.getPosition();
        if (jsonObject == null) {
            Thread.sleep(1100);
            log.info(com.smartapi.Constants.IMP_LOG+"Re-fetch positions. Count 1");
            init();
            jsonObject = historySmartConnect.getPosition();
        }
        if (jsonObject == null) {
            Thread.sleep(1100);
            log.info(com.smartapi.Constants.IMP_LOG+"Re-fetch positions. Count 2");
            init();
            jsonObject = historySmartConnect.getPosition();
        }
        if (jsonObject == null) {
            Thread.sleep(1100);
            log.info(com.smartapi.Constants.IMP_LOG+"Re-fetch positions. Count 3");
            init();
            jsonObject = historySmartConnect.getPosition();
        }
        if (jsonObject == null) {
            Thread.sleep(1100);
            log.info(com.smartapi.Constants.IMP_LOG+"Re-fetch positions. Count 4");
            init();
            jsonObject = historySmartConnect.getPosition();
        }
        if (jsonObject == null) {
            log.error(com.smartapi.Constants.IMP_LOG+"Failed in fetching positions");
            return;
        }

        JSONArray positionsJsonArray = jsonObject.optJSONArray("data");
        if (positionsJsonArray == null || positionsJsonArray.isEmpty()) {
            log.info("Empty positions, skipping\n");
            configs.setTotalPositions(0);

            return;
        }


        JSONObject orders = marketSmartConnect.getOrderHistory("v122968");
        if (orders == null) {
            log.info("Re-fetch orders. Count 1");
            Thread.sleep(1100);
            init();
            orders = marketSmartConnect.getOrderHistory("v122968");
        }
        if (orders == null) {
            log.info("Re-fetch orders. Count 2");
            Thread.sleep(1100);
            init();
            orders = marketSmartConnect.getOrderHistory("v122968");
        }


        JSONArray ordersJsonArray = orders.optJSONArray("data");
        try {
            if (now.getSecond()>=40 && now.getSecond()<=46) {
                log.info("Positions {}", positionsJsonArray.toString());
                log.info("Orders {}", ordersJsonArray.toString());
            }
        } catch (Exception e) {

        }
        processSlScheduler(ordersJsonArray, positionsJsonArray, exitALLFlag, now, configs.getSymbolExitedFromScheduler());
        //System.gc();
    }

    public void processSlScheduler(JSONArray ordersJsonArray,
                                    JSONArray positionsJsonArray,
                                    boolean exitALLFlag,
                                    LocalTime now,
                                   List<String> slSymbols) throws InterruptedException {
        boolean isExitAllPosRequired = fetch2xSlOnPositions(ordersJsonArray, positionsJsonArray);
        boolean isExitRequiredForReTradeAtSl = isExitRequiredForReTradeAtSl(ordersJsonArray, positionsJsonArray, slSymbols);

        Double mtm = 0.00;
        Double buyamount = 0.0;
        Double sellamount = 0.0;
        for (int i = 0; i < positionsJsonArray.length(); i++) {
            JSONObject pos = positionsJsonArray.optJSONObject(i);
            mtm = mtm + Double.parseDouble(pos.optString("pnl"));
            if (pos.has("sellamount")) {
                sellamount = sellamount + Double.parseDouble(pos.optString("sellamount"));
            }
            if (pos.has("buyamount")) {
                buyamount = buyamount + Double.parseDouble(pos.optString("buyamount"));
            }
        }
        boolean isTradeAllowed = true;
        if (now.isAfter(LocalTime.of(9,15)) && now.isBefore(LocalTime.of(11,28)) && mtm != 0.00) {
            isTradeAllowed = false;
            String opt = "Check manually if all trades close. Time now is not allowed. Trade after 11:28";
            log.info(opt);
            sendMail(opt);
        }
        Double modifiedMaxLoss = maxLossAmount;
        /*boolean nonExpMaxProfit = false;
        if (!isExpiry() && mtm >=0.0 && mtm>= ((double)configs.getNonExpMaxProfit())) {
            nonExpMaxProfit = true;
        }*/

        // Strict sl orders to prevent slippages
        processStrictSl(mtm, modifiedMaxLoss, ordersJsonArray, positionsJsonArray);
        configs.setMtm(mtm.intValue());
        configs.setMaxProfit((int) (sellamount - buyamount));
        if (LocalTime.now().getHour()>=20 && LocalTime.now().getHour()<=23) {
            configs.setMtm(0);
            configs.setMaxProfit(0);
        }
        boolean allClosed = areAllPosClosed(positionsJsonArray);
        if ((mtm <= 0 && Math.abs(mtm) >= modifiedMaxLoss) || exitALLFlag ||
                isExitAllPosRequired || !isTradeAllowed) {
            log.info(com.smartapi.Constants.IMP_LOG+"Flags exitALLFlag {}, isExitAllPosRequired {}, isExitRequiredForReTradeAtSl {}\n Max orders remaing {}", exitALLFlag, isExitAllPosRequired,
                    isExitRequiredForReTradeAtSl, configs.getTotalMaxOrdersAllowed());
            if (mtm>0.0) {
                if (!allClosed) {
                    sendMail("Closing positions, Profit: " + mtm);
                }
                log.info(com.smartapi.Constants.IMP_LOG+"Max Profit reached. Profit {}, starting to close all pos.\n", mtm);
            } else {
                if (!allClosed) {
                    sendMail("[SL] Max MTM loss reached. Loss: " + mtm + " Threshold: " + modifiedMaxLoss);
                }
                log.info(com.smartapi.Constants.IMP_LOG+"Max MTM loss reached. Loss {}. maxLossAmount {}, starting to close all pos.\n", mtm, modifiedMaxLoss);
            }
            try {
                log.info("Fetched orders {}\n", ordersJsonArray.toString());
                log.info("Fetched positions {}\n", positionsJsonArray.toString());
            } catch (Exception e) {

            }
            int currentOrders = configs.getTotalMaxOrdersAllowed();
            if (currentOrders < 0) {
                log.info("Max orders placed, can not place further orders");
                if (!allClosed) {
                    sendMessage.sendMessage("Max orders placed, can not place further orders");
                }
                return;
            }
            configs.setTotalMaxOrdersAllowed(currentOrders - 1);

            if (ordersJsonArray == null || ordersJsonArray.length()==0) {
                log.info("Orders array empty\n");
            } else {
                for (int i = 0; i < ordersJsonArray.length(); i++) {
                    JSONObject order = ordersJsonArray.optJSONObject(i);
                    if ("open".equals(order.optString("status")) || "trigger pending".equals(order.optString("status"))) {
                        log.info("Cancelling order {}. Symbol {}\n", order.optString("orderid"), order.optString("tradingsymbol"));
                        Order cancelOrder = tradingSmartConnect.cancelOrder(order.optString("orderid"), order.optString("variety"));
                        if (cancelOrder == null) {
                            log.info("Retry cancel order\n");
                            Thread.sleep(200);
                            cancelOrder = tradingSmartConnect.cancelOrder(order.optString("orderid"), order.optString("variety"));
                        }
                        Thread.sleep(200);
                        log.info("Cancelled order {}. Symbol {}. Order {}\n", order.optString("orderid"), order.optString("tradingsymbol"), cancelOrder);
                    }
                }
            }
            if (positionsJsonArray == null || positionsJsonArray.length()==0) {
                log.info("Empty positions, skipping\n");
                return;
            }

            JSONArray updatedPosArray = getUpdatedPosArray(positionsJsonArray);
            log.info("Trying to close all open positions: {}\n", updatedPosArray.length());
            sendMail("[SL] Trying to close all open positions: {}\n");
            for (int k = 0; k < updatedPosArray.length(); k++) {
                JSONObject pos = updatedPosArray.optJSONObject(k);
                log.info("Pos {}", pos.toString());
                if (!pos.optString("buyqty").equals(pos.optString("sellqty"))) {
                    int buyQty = Integer.valueOf(pos.optString("buyqty"));
                    int sellQty = Integer.valueOf(pos.optString("sellqty"));
                    if (buyQty < sellQty) {
                        int totalQty = Math.abs(sellQty - buyQty);
                        for (int i = 0; i < 100; i++) {
                            int maxQty = getMaxQty(pos.optString("tradingsymbol"));
                            if (totalQty > 0) {
                                int qty = 0;
                                if (totalQty <= maxQty) {
                                    qty = totalQty;
                                } else {
                                    qty = maxQty;
                                }
                                totalQty = totalQty - qty;
                                OrderParams buyOrderParams = new OrderParams();
                                buyOrderParams.variety = Constants.VARIETY_NORMAL;
                                buyOrderParams.quantity = qty;
                                buyOrderParams.symboltoken = pos.optString("symboltoken");
                                buyOrderParams.exchange = pos.optString("exchange");
                                buyOrderParams.ordertype = Constants.ORDER_TYPE_LIMIT; //
                                buyOrderParams.tradingsymbol = pos.optString("tradingsymbol");
                                buyOrderParams.producttype = pos.optString("producttype");
                                buyOrderParams.duration = Constants.DURATION_DAY;
                                buyOrderParams.transactiontype = Constants.TRANSACTION_TYPE_BUY;
                                buyOrderParams.price = roundOff(Double.parseDouble(pos.optString("ltp")) + 10.00);
                                Order order = null;
                                try {
                                    if (!isOrderAllowedAsPerThreshold()) {
                                        return;
                                    }
                                    order = tradingSmartConnect.placeOrder(buyOrderParams, Constants.VARIETY_NORMAL);
                                } catch (Exception | SmartAPIException e) {
                                    order = null;
                                    log.error("Error in placing order for {}\n", pos.optString("symboltoken"), e);
                                }

                                if (order == null) {
                                    log.info("Buy order failed processed, retrying\n");
                                    init();
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    try {
                                        if (!isOrderAllowedAsPerThreshold()) {
                                            return;
                                        }
                                        order = tradingSmartConnect.placeOrder(buyOrderParams, Constants.VARIETY_NORMAL);
                                    } catch (Exception | SmartAPIException e) {
                                        log.error("Error in placing order for {}\n", pos.optString("symboltoken"), e);
                                    }
                                }
                                Thread.sleep(200);
                                log.info("Order placed to close pos {}\n", order);
                                try {
                                    List<String> symbolsExitedFromScheduler = configs.getSymbolExitedFromScheduler();
                                    symbolsExitedFromScheduler.add(pos.optString("tradingsymbol"));
                                    configs.setSymbolExitedFromScheduler(symbolsExitedFromScheduler);
                                } catch (Exception e) {
                                    log.error("Error in setting symbolsExitedFromScheduler ", e);
                                }
                            } else {
                                break;
                            }
                        }
                    } else {
                        int totalQty = Math.abs(buyQty - sellQty);
                        for (int i = 0; i < 100; i++) {
                            if (totalQty > 0) {
                                int maxQty = getMaxQty(pos.optString("tradingsymbol"));
                                int qty = 0;
                                if (totalQty <= maxQty) {
                                    qty = totalQty;
                                } else {
                                    qty = maxQty;
                                }
                                totalQty = totalQty - qty;
                                OrderParams sellOrderParams = new OrderParams();
                                sellOrderParams.variety = Constants.VARIETY_NORMAL;
                                sellOrderParams.quantity = qty;
                                sellOrderParams.symboltoken = pos.optString("symboltoken");
                                sellOrderParams.exchange = pos.optString("exchange");
                                sellOrderParams.ordertype = Constants.ORDER_TYPE_LIMIT; //
                                sellOrderParams.tradingsymbol = pos.optString("tradingsymbol");
                                sellOrderParams.producttype = pos.optString("producttype");
                                sellOrderParams.duration = Constants.DURATION_DAY;
                                sellOrderParams.transactiontype = Constants.TRANSACTION_TYPE_SELL;
                                Double sellPrice = Double.parseDouble(pos.optString("ltp")) - 5.00;
                                if (sellPrice <= 1.0) {
                                    sellPrice = 0.1;
                                }
                                sellOrderParams.price = roundOff(sellPrice);
                                Order order = null;
                                try {
                                    if (!isOrderAllowedAsPerThreshold()) {
                                        return;
                                    }
                                    order = tradingSmartConnect.placeOrder(sellOrderParams, Constants.VARIETY_NORMAL);
                                } catch (Exception | SmartAPIException e) {
                                    order = null;
                                    log.error("Error in placing order for {}", pos.optString("symboltoken"), e);
                                }

                                if (order == null) {
                                    log.info("Sell order failed processed, retrying");
                                    init();
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    try {
                                        if (!isOrderAllowedAsPerThreshold()) {
                                            return;
                                        }
                                        order = tradingSmartConnect.placeOrder(sellOrderParams, Constants.VARIETY_NORMAL);
                                    } catch (Exception | SmartAPIException e) {
                                        log.error("Error in placing order for {}", pos.optString("symboltoken"), e);
                                    }
                                }
                                Thread.sleep(200);
                                log.info("Order placed to close pos {}", order);
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
            log.info("[Probable closed all positions]. Validate manually\n");
            if (!allClosed) {
                sendMail("[SL] [Probable closed all positions]. Validate manually\n");
            }
            try {
                configs.setMaxLossEmailCount(configs.getMaxLossEmailCount() - 1);
                configs.setSlHitSmsSent(true);
            } catch (Exception e) {
                log.error("Error in updating configs with sl hit\n");
            }

        } else {
            log.info("[Max loss tracker]. Threshold: {}, MTM {}, Max Profit possible {} \n Max orders remaining {}\n at time {}\n", maxLossAmount, mtm, sellamount - buyamount,
                    configs.getTotalMaxOrdersAllowed(), now);
        }
    }

    private boolean isOrderAllowedAsPerThreshold() {
        if (LocalTime.now().isBefore(LocalTime.of(15, 30))) {
            return true;
        }

        int currentOrders = configs.getTotalMaxOrdersAllowed();
        if (currentOrders < 0) {
            log.info("Max orders placed, can not place further orders");
            return false;
        }
        configs.setTotalMaxOrdersAllowed(currentOrders - 1);
        return true;
    }

    private void processStrictSl(Double mtm,
                                 Double modifiedMaxLoss,
                                 JSONArray ordersJsonArray,
                                 JSONArray positionsJsonArray) {
        try {
                int i;
                String sellOptionSymbol = "";
                double ltp = 0.0;
                double netQty = 1.0;

                String productType = "";
                String token = "";
                 double sellAvgPrice = 0.0;
                for (i = 0; i < positionsJsonArray.length(); i++) {
                    JSONObject pos = positionsJsonArray.optJSONObject(i);
                    if (pos != null && pos.optString("netqty").contains("-")) {
                        sellOptionSymbol = pos.optString("tradingsymbol");
                        ltp = Double.valueOf(pos.optString("ltp"));
                        netQty = Double.valueOf(pos.optDouble("netqty"));
                        productType = pos.optString("producttype");
                        token = pos.optString("symboltoken");
                        sellAvgPrice = Double.parseDouble(pos.optString("sellavgprice"));
                        break;
                    }
                }
                if (sellOptionSymbol.isEmpty()) {
                    log.info("Empty sell pos for strict sl, returning");
                    return;
                }

                if (netQty < 0) {
                    netQty = - netQty;
                }

                int indexMaxSellQty;
                if (sellOptionSymbol.startsWith("MIDCPNIFTY")) {
                    indexMaxSellQty = configs.getOiBasedTradeMidcapQty();
                } else if (sellOptionSymbol.startsWith("NIFTY")) {
                    indexMaxSellQty = configs.getOiBasedTradeQtyNifty();
                } else if (sellOptionSymbol.startsWith("BANKNIFTY")) {
                    indexMaxSellQty = configs.getOiBasedTradeBankNiftyQty();
                } else if (sellOptionSymbol.startsWith(SENSEX)) {
                    indexMaxSellQty = configs.getOiBasedTradeSensexQty();
                } else {
                    indexMaxSellQty = configs.getOiBasedTradeQtyFinNifty();
                }

                boolean isStrictSlAlreadyPlaced = false;
                int orderQty = 0;
                if (ordersJsonArray != null || ordersJsonArray.length() > 0) {
                    for (i = 0; i < ordersJsonArray.length(); i++) {
                        JSONObject order = ordersJsonArray.optJSONObject(i);
                        String orderSymbol = order.optString("tradingsymbol");
                        if ("open".equals(order.optString("status")) || "trigger pending".equals(order.optString("status"))) {
                            if (orderSymbol.equals(sellOptionSymbol) && "BUY".equals(order.optString("transactiontype"))) {
                                orderQty += Integer.parseInt(order.optString("quantity"));
                            }
                        }
                    }
                }
                // initial sl placed or not

                if (orderQty == (int) netQty) {
                    isStrictSlAlreadyPlaced = true;
                }

                if (!(LocalTime.now().isAfter(LocalTime.of(9, 15)) && LocalTime.now().isBefore(LocalTime.of(15, 30)))) {
                    log.info("Strict sl skipped as non trading hours now");
                    return;
                }

                if (!isStrictSlAlreadyPlaced) {
                    log.info("Init strict sl process");
                    if (ordersJsonArray == null || ordersJsonArray.length() == 0) {
                        log.info("[processStrictSl] Orders array empty");
                    } else {
                        for (i = 0; i < ordersJsonArray.length(); i++) {
                            JSONObject order = ordersJsonArray.optJSONObject(i);
                            if (("open".equals(order.optString("status")) || "trigger pending".equals(order.optString("status")))
                                    && "BUY".equals(order.optString("transactiontype"))) {
                                log.info("[processStrictSl] Cancelling order {}. Symbol {}", order.optString("orderid"), order.optString("tradingsymbol"));
                                Order cancelOrder = tradingSmartConnect.cancelOrder(order.optString("orderid"), order.optString("variety"));
                                if (cancelOrder == null) {
                                    log.info("Retry cancel order");
                                    Thread.sleep(200);
                                    cancelOrder = tradingSmartConnect.cancelOrder(order.optString("orderid"), order.optString("variety"));
                                }
                                Thread.sleep(200);
                                log.info("[processStrictSl] Cancelled order {}. Symbol {}. Order {}", order.optString("orderid"), order.optString("tradingsymbol"), cancelOrder);
                            }
                        }
                        log.info(com.smartapi.Constants.IMP_LOG+"[processStrictSl] Cancelled all sl orders");
                    }

                    Double ltpLimit;
                    if (sellOptionSymbol.startsWith("MIDCPNIFTY")) {
                        ltpLimit = 3.0;
                    } else if (sellOptionSymbol.startsWith("NIFTY")) {
                        ltpLimit = 5.0;
                    } else if (sellOptionSymbol.startsWith("BANKNIFTY")) {
                        ltpLimit = 9.0;
                    } else if (sellOptionSymbol.startsWith(SENSEX)) {
                        ltpLimit = 20.0;
                    } else { // finnifty
                        ltpLimit = 5.0;
                    }

                    if (!sellOptionSymbol.isEmpty()) {
                        double triggerPriceForBuy = (sellAvgPrice <= ltpLimit) ? ((1 + rTorMaxOiTrade) * sellAvgPrice) : (2 * sellAvgPrice * rTorOiTrade);
                        int totalQty = (int) netQty;
                        log.info("Found sold pos for strict sl {}. Trigger price: {}, total Qty {}", sellOptionSymbol, triggerPriceForBuy, totalQty);
                        int maxQty = maxQty(sellOptionSymbol);

                        int fullBatches = totalQty / maxQty;
                        int remainingQty = totalQty % maxQty;
                        int lotSize = fetchLotSize(sellOptionSymbol);
                        remainingQty = (remainingQty % (int) lotSize == 0) ? remainingQty : remainingQty - (remainingQty % (int) lotSize);

                        String opt;
                        for (i = 0; i < fullBatches; i++) {

                            Order order = slOrder(sellOptionSymbol, token, triggerPriceForBuy, maxQty, Constants.TRANSACTION_TYPE_BUY, productType);
                            if (order != null) {
                                opt = String.format("SL order placed for %s, qty %d", sellOptionSymbol, maxQty);
                                log.info(opt);
                            } else {
                                opt = String.format("SL order failed for %s, qty %d", sellOptionSymbol, maxQty);
                                log.info(opt);
                            }
                        }
                        if (remainingQty > 0) {
                            Order order = slOrder(sellOptionSymbol, token, triggerPriceForBuy, remainingQty, Constants.TRANSACTION_TYPE_BUY, productType);
                            if (order != null) {
                                opt = String.format("SL order placed for %s, qty %d", sellOptionSymbol, remainingQty);
                                log.info(opt);
                            } else {
                                opt = String.format("SL order failed for %s, qty %d", sellOptionSymbol, remainingQty);
                                log.info(opt);
                            }
                        }
                        log.info(com.smartapi.Constants.IMP_LOG+"Placed strict sl orders");
                    }
                } else {
                    log.info("Strict sl orders already placed for qty {}, returning", orderQty);
                }
        } catch (Exception e) {
            log.error(com.smartapi.Constants.IMP_LOG+"Error in sl trigger trades", e);
        }
    }

    private int fetchLotSize(String symbol) {
        int lotSize;
        if (symbol.startsWith("MIDCPNIFTY")) {
            lotSize = configs.getMidcapNiftyLotSize();
        } else if (symbol.startsWith("NIFTY")) {
            lotSize = configs.getNiftyLotSize();
        } else if (symbol.startsWith("BANKNIFTY")) {
            lotSize = configs.getBankNiftyLotSize();
        } else if (symbol.startsWith(SENSEX)) {
            lotSize = configs.getSensexLotSize();
        } else {
            lotSize = configs.getFinniftyLotSize();
        }
        return lotSize;
    }

    private int maxQty(String symbol) {
        int maxQty;
        if (symbol.contains("MIDCPNIFTY")) {
            maxQty = 4200;
        } else if (symbol.contains("BANKNIFTY")) {
            maxQty = 900;
        } else if (symbol.contains(SENSEX)) {
            maxQty = 1000;
        } else {
            maxQty = 1800;
        }
        return maxQty;
    }

    private int getMaxQty(String symbol) {
        int maxQty = 500;
        if (symbol.contains("MIDCPNIFTY")) {
            maxQty = 4200;
        } else if (symbol.contains("BANKNIFTY")) {
            maxQty = 900;
        } else if (symbol.contains(SENSEX)) {
            maxQty = 1000;
        } else {
            maxQty = 1800;
        }
        return maxQty;
    }

    private JSONArray getUpdatedPosArray(JSONArray positionsJsonArray) {
        JSONArray updatedPos = new JSONArray();
        JSONArray soldPos = new JSONArray();
        JSONArray buyPos = new JSONArray();
        for (int i = 0; i < positionsJsonArray.length(); i++) {
            JSONObject jsonObject = positionsJsonArray.optJSONObject(i);
            if (jsonObject.optString("netqty").contains("-")) {
                // sold pos
                soldPos.put(jsonObject);
            } else {
                buyPos.put(jsonObject);
            }
        }
        for (int i = 0; i < soldPos.length(); i++) {
            updatedPos.put(soldPos.optJSONObject(i));
        }
        for (int i = 0; i < buyPos.length(); i++) {
            updatedPos.put(buyPos.optJSONObject(i));
        }
        return updatedPos;
    }

    private boolean isExitRequiredForReTradeAtSl(JSONArray ordersJsonArray, JSONArray positionsJsonArray, List<String> symbolExitedFromScheduler) {
        int i, j;
        String sellOptionSymbol = "";
        Double ltp = 0.0;
        Double soldPrice = 0.0;
        // check if there is any open pos with symbolExitedFromScheduler
        /*for (i = 0; i < positionsJsonArray.length(); i++) {
            JSONObject pos = positionsJsonArray.optJSONObject(i);
            if (pos != null && pos.optString("netqty").contains("-")) {
                sellOptionSymbol = pos.optString("tradingsymbol");
                ltp = Double.valueOf(pos.optString("ltp"));
                if (symbolExitedFromScheduler.contains(sellOptionSymbol)) {
                    // find its order
                    for (j = ordersJsonArray.length() - 1; j >= 0; j--) {
                        JSONObject order = ordersJsonArray.optJSONObject(j);
                        if (order != null && sellOptionSymbol.equals(order.optString("tradingsymbol"))
                                && "complete".equals(order.optString("orderstatus")) && "SELL".equals(order.optString("transactiontype"))) {
                            soldPrice = order.optDouble("averageprice");
                            break;
                        }
                    }
                    Double price = 10.0;
                    if (soldPrice >= price) {
                        String error = String.format("Re-trade (whose sl was hit earlier) found for option at price above %s, for symbol %s. Will close all pos, check manually also", price, sellOptionSymbol);
                        log.error(error);
                        sendMail(error);
                        // sold price is above 10 and it is a retrade whose sl was hit earlier
                        return true;
                    }
                }
            }
        }*/
        return false;
    }

    /*private boolean isExpiry() {
        if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY) ||
                LocalDate.now().getDayOfWeek().equals(DayOfWeek.TUESDAY)) {
            return true;
        }
        return false;
    }*/

    private boolean areAllPosClosed(JSONArray positionsJsonArray) {
        int i;
        try {
            if (positionsJsonArray == null) {
                return false;
            }
            for (i = 0; i < positionsJsonArray.length(); i++) {
                JSONObject pos = positionsJsonArray.optJSONObject(i);
                if (pos != null && !pos.optString("netqty").equals("0")) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.error("Error in finding if all pos closed", e);
        }
        return false;
    }

    private boolean fetch2xSlOnPositions(JSONArray ordersJsonArray, JSONArray positionsJsonArray) {
        try {
            int i;
            String sellOptionSymbol = "";
            Double ltp = 0.0;
            Double soldPrice = 0.0;
            if (ordersJsonArray == null || ordersJsonArray.length()==0 || positionsJsonArray == null || positionsJsonArray.length()==0) {
                //log.info("Empty order or position array, skipping 2x sl check");
                return false;
            } else {
                for (i = 0; i < positionsJsonArray.length(); i++) {
                    JSONObject pos = positionsJsonArray.optJSONObject(i);
                    if (pos != null && pos.optString("netqty").contains("-")) {
                        configs.setTotalPositions(configs.getTotalPositions() + 1);
                        sellOptionSymbol = pos.optString("tradingsymbol");
                        ltp = Double.valueOf(pos.optString("ltp"));
                        configs.setSoldOptionLtp(ltp);

                        break;
                    }
                }
                LocalTime now = LocalTime.now();
                // To handle volatility
                boolean manualTradePlaced = false;
                for (i = 0; i < positionsJsonArray.length(); i++) {
                    JSONObject pos = positionsJsonArray.optJSONObject(i);
                    if (pos != null && !pos.optString("netqty").equals("0")) {
                        if (configs.getTradedOptions() != null && !configs.getTradedOptions().isEmpty()
                        && !configs.getTradedOptions().contains(pos.optString("tradingsymbol"))
                        && now.isBefore(LocalTime.of(15, 25))) {
                            manualTradePlaced = true;
                        }
                    }
                }
                if (manualTradePlaced) {
                    log.info("Manual trade not allowed now, closing pos");
                    sendMessage.sendMessage(com.smartapi.Constants.IMP_LOG + "Manual trade not allowed now, closing pos");
                    return true;
                }

                if (sellOptionSymbol.isEmpty()) {
                    log.info("Sell pos not found, skipping");
                    return false;
                } else {
                    log.info("Sell pos: {}", sellOptionSymbol);
                }

                // orders are in ascending order of time
                for (i = ordersJsonArray.length() - 1; i >= 0; i--) {
                    JSONObject order = ordersJsonArray.optJSONObject(i);
                    if (order != null && sellOptionSymbol.equals(order.optString("tradingsymbol")) && "complete".equals(order.optString("orderstatus"))) {
                        soldPrice = order.optDouble("averageprice");
                        break;
                    }
                }
                Double slPrice;
                if (soldPrice < 5.0) {
                    //slPrice = 2 * soldPrice + 3;
                    slPrice = 16.0;
                } else if (soldPrice >= 5.0 && soldPrice <= 10.0) {
                    slPrice = 2 * soldPrice + 6;
                } else {
                    slPrice = 2 * soldPrice + 6;
                }
                boolean slHitRequired = (ltp >= slPrice);

                // if trade is placed on basis of oi crossover and after that oi reverse cross, exit trade
                String mapKey = sellOptionSymbol.endsWith("CE") ? sellOptionSymbol
                        : sellOptionSymbol.replace("PE", "CE");
                boolean isCE = sellOptionSymbol.endsWith("CE");

                OiTrade oiTrade = configs.getOiTradeMap().getOrDefault(mapKey, null);
                boolean exitReqOnBasisOfOi = false;
                int buffer = 100000;
                if (oiTrade!=null) {
                    int ceOi = oiTrade.getCeOi();
                    int peOi = oiTrade.getPeOi();
                    if (isCE) {
                        if (peOi - ceOi >= buffer) {
                            exitReqOnBasisOfOi = true;
                        }
                    } else {
                        if (ceOi - peOi >= buffer) {
                            exitReqOnBasisOfOi = true;
                        }
                    }
                }

                if (LocalTime.now().isBefore(LocalTime.of(15, 30))) {
                    if (slHitRequired) {
                        String slHitReq = String.format(com.smartapi.Constants.IMP_LOG+"Sl hit required for 2x sl. symbol %s, sl price %s. Will close all pos, check manually also", sellOptionSymbol, slPrice);
                        log.info(slHitReq);
                        sendMail(slHitReq);
                    } else if (exitReqOnBasisOfOi) {
                        String slHitReq = String.format(com.smartapi.Constants.IMP_LOG+"EXIT required because reverse oi crossover. symbol %s. Will close all pos, check manually also", sellOptionSymbol);
                        log.info(slHitReq);
                        sendMail(slHitReq);

                        configs.setOiBasedTradePlaced(false);
                        slHitReq = String.format("Reset oi based trade to false", sellOptionSymbol);
                        log.info(slHitReq);
                        sendMail(slHitReq);

                        //configs.setTradedOptions(new ArrayList<>());

                    }
                } else {
                    log.info("Skipping sl hit required, as market is closed");
                }
                return slHitRequired || exitReqOnBasisOfOi;
            }
        } catch (Exception e) {
            log.error("Error in checking 2x sl", e);
            return false;
        }
    }

    public Order placeOrder(String tradeSymbol, String tradeToken, Double price, Integer qty, String transactionType, Double triggerPrice) {
        Order order = null;
        int currentOrders = configs.getTotalMaxOrdersAllowed();
        if (currentOrders < 0) {
            log.info("Max orders placed, can not place further orders");
            sendMessage.sendMessage("Max orders placed, can not place further orders");
            return null;
        }
        configs.setTotalMaxOrdersAllowed(currentOrders - 1);

        OrderParams orderParams = new OrderParams();
        orderParams.variety = Constants.VARIETY_NORMAL;
        orderParams.quantity = qty;
        orderParams.symboltoken = tradeToken;
        orderParams.exchange = tradeSymbol.startsWith(SENSEX) ? "BFO" : "NFO";
        orderParams.ordertype = Constants.ORDER_TYPE_LIMIT; //
        orderParams.tradingsymbol = tradeSymbol;
        orderParams.producttype = Constants.PRODUCT_CARRYFORWARD;
        orderParams.duration = Constants.DURATION_DAY;
        orderParams.transactiontype = transactionType;
        if (transactionType.equals(Constants.TRANSACTION_TYPE_BUY)) {
            orderParams.price = roundOff(price + 5.00);
        } else {
            Double sellPrice = price - 5.00;
            if (sellPrice <= 1.0) {
                sellPrice = 0.1;
            }

            if (triggerPrice > 0.0) {
                orderParams.triggerprice = String.valueOf(roundOff(triggerPrice));
                orderParams.variety = Constants.VARIETY_STOPLOSS;
                orderParams.ordertype = Constants.ORDER_TYPE_STOPLOSS_LIMIT;
                orderParams.price = roundOff(roundOff(triggerPrice) - 0.5);
            } else {
                orderParams.price = roundOff(sellPrice);
            }
        }
        try {
            order = tradingSmartConnect.placeOrder(orderParams, orderParams.variety);
        } catch (Exception | SmartAPIException e) {
            order = null;
            log.error("Error in placing order for {}", tradeSymbol, e);
        }
        if (order == null) {
            log.info("Buy order failed processed, retrying");
            init();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                log.error("Sleep exception");
            }
            try {
                order = tradingSmartConnect.placeOrder(orderParams, orderParams.variety);
            } catch (Exception | SmartAPIException e) {
                log.error("Error in placing order for {}", tradeSymbol, e);
            }
        }
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            log.error("Sleep exception");
        }
        return order;
    }

    public Order slOrder(String tradeSymbol,
                         String tradeToken,
                         Double price,
                         Integer qty,
                         String transactionType,
                         String productType) {
        Order order = null;
        int currentOrders = configs.getTotalMaxOrdersAllowed();
        if (currentOrders < 0) {
            log.info("Max orders placed, can not place further orders");
            sendMessage.sendMessage("Max orders placed, can not place further orders");
            return null;
        }
        configs.setTotalMaxOrdersAllowed(currentOrders - 1);

        OrderParams orderParams = new OrderParams();
        orderParams.variety = Constants.VARIETY_STOPLOSS;
        orderParams.quantity = qty;
        orderParams.symboltoken = tradeToken;
        orderParams.exchange = tradeSymbol.startsWith(SENSEX) ? "BFO" : "NFO";
        orderParams.ordertype = Constants.ORDER_TYPE_STOPLOSS_LIMIT; //
        orderParams.tradingsymbol = tradeSymbol;
        orderParams.producttype = productType;
        orderParams.duration = Constants.DURATION_DAY;
        orderParams.transactiontype = transactionType;

        orderParams.price = roundOff(price + 5.00);
        orderParams.triggerprice = String.valueOf(roundOff(price));

        try {
            order = tradingSmartConnect.placeOrder(orderParams, orderParams.variety);
        } catch (Exception | SmartAPIException e) {
            order = null;
            log.error("Error in placing order for {}", tradeSymbol, e);
        }
        if (order == null) {
            log.info("Buy order failed processed, retrying");
            init();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                log.error("Sleep exception");
            }
            try {
                order = tradingSmartConnect.placeOrder(orderParams, orderParams.variety);
            } catch (Exception | SmartAPIException e) {
                log.error("Error in placing order for {}", tradeSymbol, e);
            }
        }
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            log.error("Sleep exception");
        }
        return order;
    }

    public Double roundOff(Double val) {
        return Math.round(val*10.0)/10.0;
    }

    private void sendMail(String msg) {
        try {
            if (configs.getMaxLossEmailCount() > 0) {
                sendMessage.sendMessage(msg);
            }
        } catch (Exception e) {
            log.error("Error sending mail {}", msg);
        }
    }

    @Scheduled(cron = "0 15 8 * * *")
    public void reInitSession() {
        boolean initCt = false;
        for (int i = 0; i <= 5; i++) {
            try {
                init();
                initCt = true;
                break;
            } catch (Exception e) {
                log.error("Error in re-init session, Retrying");
            }
        }
        if (initCt == false) {
            log.error("Failed to re init session");
            sendMessage.sendMessage("Failed to re init session");
        } else {
            log.info("re init session success");
        }
        configs.setSlHitSmsSent(false);
    }

    public SmartConnect TradingSmartConnect (String totp) throws Exception, SmartAPIException {
        log.info("Setting max loss email count");

        log.info("Creating TradingSmartConnect, totp {}", totp);
        SmartConnect smartConnect = new SmartConnect();
        smartConnect.setApiKey(configs.getTradingPrivateKey());
        User user = smartConnect.generateSession("V122968", configs.getPassword(), totp);
        log.info("User {}", user.toString());

        if (user.getAccessToken()==null) {
            Exception e = new Exception("Error in token creation");
            log.error("Error in token creation", e);
            throw e;
        }
        smartConnect.setAccessToken(user.getAccessToken());
        smartConnect.setUserId(user.getUserId());
        log.info("Created TradingSmartConnect. Token {}", user.getAccessToken());
        Thread.sleep(800);
        return smartConnect;
    }

    public SmartConnect historySmartConnect(String totp) throws Exception, SmartAPIException {
        log.info("Creating historySmartConnect. key {}", configs.getHistoryPrivateKey());
        SmartConnect smartConnect = new SmartConnect();
        smartConnect.setApiKey(configs.getHistoryPrivateKey());
        User user = smartConnect.generateSession("V122968", configs.getPassword(), totp);
        log.info("User {}", user.toString());

        if (user.getAccessToken()==null) {
            Exception e = new Exception("Error in token creation");
            log.error("Error in token creation", e);
            throw e;
        }
        smartConnect.setAccessToken(user.getAccessToken());
        smartConnect.setUserId(user.getUserId());
        log.info("Created historySmartConnect. Token {}", user.getAccessToken());
        Thread.sleep(800);
        return smartConnect;
    }

    public SmartConnect MarketSmartConnect(String totp) throws Exception, SmartAPIException {
        log.info("Creating MarketSmartConnect");
        SmartConnect smartConnect = new SmartConnect();
        smartConnect.setApiKey(configs.getMarketPrivateKey());
        User user = smartConnect.generateSession("V122968", configs.getPassword(), totp);
        log.info("User {}", user.toString());

        if (user.getAccessToken()==null) {
            Exception e = new Exception("Error in token creation");
            log.error("Error in token creation", e);
            throw e;
        }

        smartConnect.setAccessToken(user.getAccessToken());
        smartConnect.setUserId(user.getUserId());
        log.info("Created MarketSmartConnect. Token {}", user.getAccessToken());
        Thread.sleep(800);
        return smartConnect;
    }
}

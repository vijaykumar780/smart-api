package com.smartapi.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.models.OrderParams;
import com.angelbroking.smartapi.models.User;
import com.angelbroking.smartapi.utils.Constants;
import com.smartapi.Configs;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static java.time.temporal.ChronoUnit.SECONDS;

@Log4j2
@Service
public class StopAtMaxLossScheduler {

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
    int maxQty = 1800;

    @PostConstruct
    public void init() {
        boolean initCt = false;

        String totp;// = configs.getTotps().get(0);
        //configs.getTotps().remove(0);
        GoogleAuthenticator gAuth = new GoogleAuthenticator();
        totp = String.valueOf(gAuth.getTotpPassword(configs.getTotp()));

        log.info("Using totp {}", totp);
        for (int i = 0; i <= 5; i++) {
            try {
                log.info("Re-initiating session");
                this.tradingSmartConnect = TradingSmartConnect(totp);
                this.marketSmartConnect = MarketSmartConnect(totp);
                this.historySmartConnect = historySmartConnect(totp);
                initCt = true;
                break;
            } catch (Exception | SmartAPIException e) {
                log.error("Error in re-init session, Retrying ", e);
            }
        }
        if (initCt == false) {
            sendMessage.sendMessage("Failed to re init session");
            log.info("Reinited failed");
        } else {
            log.info("Reinited success");
        }
    }

    @Scheduled(fixedDelay = 2000) // (fixedDelay = 150000)
    public void stopOnMaxLoss() throws InterruptedException {
        stopOnMaxLossProcess(false);
    }

    public void stopOnMaxLossProcess(boolean exitALLFlag) throws InterruptedException {
        LocalTime localStartTimeMarket = LocalTime.of(8,0,0);
        LocalTime localEndTime = LocalTime.of(23,50,1);
        LocalTime localEndTimeMarket = LocalTime.of(15,30,1);
        LocalTime now = LocalTime.now();
        if (!(now.isAfter(localStartTimeMarket) && now.isBefore(localEndTime))) {
            log.info("Current time {} is beyond range {} to {}. Threshold: {}", now, localStartTimeMarket, localEndTime, maxLossAmount);
            return;
        }
        log.info("Starting Max loss tracker. Threshold: {} at time {}", maxLossAmount, now);
        if (historySmartConnect == null || tradingSmartConnect == null || marketSmartConnect ==null) {
            init();
        }
        JSONObject jsonObject = historySmartConnect.getPosition();
        if (jsonObject == null) {
            Thread.sleep(1100);
            log.info("Re-fetch positions");
            init();
            jsonObject = historySmartConnect.getPosition();
        }
        if (jsonObject == null) {
            log.error("Failed to fetch positions");
            return;
        }
        log.info("Fetched positions {}", jsonObject.toString());
        JSONArray positionsJsonArray = jsonObject.optJSONArray("data");
        if (positionsJsonArray == null || positionsJsonArray.isEmpty()) {
            log.info("Empty positions, skipping");
            return;
        }

        Double buyamount =0.0;
        Double sellamount =0.0;

        Double mtm = 0.00;
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
        log.info("Fetching orders");
        JSONObject orders = marketSmartConnect.getOrderHistory("v122968");
        if (orders == null) {
            log.info("Re-fetch orders");
            Thread.sleep(1100);
            orders = marketSmartConnect.getOrderHistory("v122968");
        }

        log.info("Fetched Orders {}", orders.toString());
        JSONArray ordersJsonArray = orders.optJSONArray("data");
        boolean isExitAllPosRequired = fetch2xSlOnPositions(ordersJsonArray, positionsJsonArray);
        if ((mtm <= 0 && Math.abs(mtm) >= maxLossAmount) || exitALLFlag || isExitAllPosRequired) {
            sendMail("[SL] Max MTM loss reached. Loss: " + mtm + " Threshold: " + maxLossAmount);
            log.info("Max MTM loss reached. Loss {}. maxLossAmount {}, starting to close all pos.", mtm, maxLossAmount);

            if (ordersJsonArray == null || ordersJsonArray.isEmpty()) {
                log.info("Orders array empty");
            } else {
                for (int i = 0; i < ordersJsonArray.length(); i++) {
                    JSONObject order = ordersJsonArray.optJSONObject(i);
                    if ("open".equals(order.optString("status")) || "trigger pending".equals(order.optString("status"))) {
                        log.info("Cancelling order {}. Symbol {}", order.optString("orderid"), order.optString("tradingsymbol"));
                        Order cancelOrder = tradingSmartConnect.cancelOrder(order.optString("orderid"), order.optString("variety"));
                        if (cancelOrder == null) {
                            log.info("Retry cancel order");
                            Thread.sleep(290);
                            cancelOrder = tradingSmartConnect.cancelOrder(order.optString("orderid"), order.optString("variety"));
                        }
                        Thread.sleep(290);
                        log.info("Cancelled order {}. Symbol {}. Order {}", order.optString("orderid"), order.optString("tradingsymbol"), cancelOrder);
                    }
                }
            }
            if (positionsJsonArray == null || positionsJsonArray.isEmpty()) {
                log.info("Empty positions, skipping");
                return;
            }
            log.info("Trying to close all open positions: {}", positionsJsonArray.length());
            sendMail("[SL] Trying to close all open positions: {}");
            for (int k = 0; k < positionsJsonArray.length(); k++) {
                JSONObject pos = positionsJsonArray.optJSONObject(k);
                log.info("Pos {}", pos.toString());
                if (!pos.optString("buyqty").equals(pos.optString("sellqty"))) {
                    int buyQty = Integer.valueOf(pos.optString("buyqty"));
                    int sellQty = Integer.valueOf(pos.optString("sellqty"));
                    if (buyQty < sellQty) {
                        int totalQty = Math.abs(sellQty - buyQty);
                        for (int i = 0; i < 100; i++) {
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
                                Order order = tradingSmartConnect.placeOrder(buyOrderParams, Constants.VARIETY_NORMAL);
                                if (order == null) {
                                    log.info("Buy order failed to processed, retrying");
                                    init();
                                    try {
                                        Thread.sleep(290);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    order = tradingSmartConnect.placeOrder(buyOrderParams, Constants.VARIETY_NORMAL);
                                }
                                Thread.sleep(290);
                                log.info("Order placed to close pos {}", order);
                            } else {
                                break;
                            }
                        }
                    } else {
                        int totalQty = Math.abs(buyQty - sellQty);
                        for (int i = 0; i < 100; i++) {
                            if (totalQty > 0) {
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
                                sellOrderParams.price = roundOff(Double.parseDouble(pos.optString("ltp")) - 2.00);
                                Order order = tradingSmartConnect.placeOrder(sellOrderParams, Constants.VARIETY_NORMAL);

                                if (order == null) {
                                    log.info("Buy order failed to processed, retrying");
                                    init();
                                    try {
                                        Thread.sleep(290);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    order = tradingSmartConnect.placeOrder(sellOrderParams, Constants.VARIETY_NORMAL);
                                }
                                Thread.sleep(290);
                                log.info("Order placed to close pos {}", order);
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
            log.info("[Probable closed all positions]. Validate manually");
            sendMail("[SL] [Probable closed all positions]. Validate manually");
            configs.setMaxLossEmailCount(configs.getMaxLossEmailCount() - 1);
            configs.setSlHitSmsSent(true);
        } else {
            log.info("MTM: {}", mtm);
            log.info("Max Profit possible {}", sellamount - buyamount);
        }
        log.info("Finished Max loss tracker at {}, time taken {} seconds", LocalTime.now(), SECONDS.between(now, LocalTime.now()));
    }

    private boolean fetch2xSlOnPositions(JSONArray ordersJsonArray, JSONArray positionsJsonArray) {
        try {
            int i;
            String sellOptionSymbol = "";
            Double ltp = 0.0;
            Double soldPrice = 0.0;
            if (ordersJsonArray == null || ordersJsonArray.isEmpty() || positionsJsonArray == null || positionsJsonArray.isEmpty()) {
                log.info("Empty order or position array, skipping 2x sl check");
                return false;
            } else {
                for (i = 0; i < positionsJsonArray.length(); i++) {
                    JSONObject pos = positionsJsonArray.optJSONObject(i);
                    if (pos != null && pos.optString("netqty").contains("-")) {
                        sellOptionSymbol = pos.optString("tradingsymbol");
                        ltp = Double.valueOf(pos.optString("ltp"));
                        break;
                    }
                }
                if (sellOptionSymbol.isEmpty()) {
                    log.info("Sell pos not found, skipping");
                    return false;
                }
                List<OrderData> orderDataList = new ArrayList<>();
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
                    slPrice = 2 * soldPrice + 3;
                } else if (soldPrice >= 5.0 && soldPrice <= 10.0) {
                    slPrice = 2 * soldPrice + 2;
                } else {
                    slPrice = 2 * soldPrice;
                }
                boolean slHitRequired = (ltp >= slPrice);
                log.info("[2x sl check] Symbol: {}, soldPrice: {}, ltp: {}, Sl price: {}, sl Hit Required: {}", sellOptionSymbol, soldPrice, ltp, slPrice, slHitRequired);
                return slHitRequired;
            }
        } catch (Exception e) {
            log.error("Error in checking 2x sl", e);
            return false;
        }
    }


    public Double roundOff(Double val) {
        return Math.round(val*10.0)/10.0;
    }

    private void sendMail(String msg) {
        if (configs.getMaxLossEmailCount() > 0) {
            sendMessage.sendMessage(msg);
        }
    }

    @Scheduled(cron = "0 5 7 * * *")
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

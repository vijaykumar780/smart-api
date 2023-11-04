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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static java.time.temporal.ChronoUnit.SECONDS;

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

    @PostConstruct
    public void init() {
        boolean initCt = false;

        String totp;// = configs.getTotps().get(0);
        //configs.getTotps().remove(0);
        GoogleAuthenticator gAuth = new GoogleAuthenticator();
        totp = String.valueOf(gAuth.getTotpPassword(configs.getTotp()));

        log.info("Initiating smart connects. Using totp {}", totp);
        for (int i = 0; i <= 5; i++) {
            try {
                log.info("Re-initiating session");
                this.tradingSmartConnect = TradingSmartConnect(totp);
                this.marketSmartConnect = MarketSmartConnect(totp);
                this.historySmartConnect = historySmartConnect(totp);
                configs.setTokenForMarketData(marketSmartConnect.getAccessToken());
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

    @Scheduled(fixedDelay = 50000)
    public void gc() {
        System.gc();
        log.info("GC, Total memory {}, Free memory {}, Max memory {}", Runtime.getRuntime().totalMemory()/1000000,
                Runtime.getRuntime().freeMemory()/1000000, Runtime.getRuntime().maxMemory()/1000000);
    }

    @Scheduled(fixedDelay = 1000) // (fixedDelay = 150000)
    public void stopOnMaxLoss() throws Exception {
        memoryAlarmChecker();
        stopOnMaxLossProcess(false);
    }

    private void memoryAlarmChecker() {
        if (LocalTime.now().getSecond() % 50 == 0) {
            String s;
            Process p;
            try {
                p = Runtime.getRuntime().exec("free -h");
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String memoryLine = "";
                int c = 0;
                String mems[];
                int memoryRemaining = 0;
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
                                } else if (memCnt == 6 || memCnt == 7) {
                                    memoryRemaining = memoryRemaining + Integer.parseInt(op.substring(0, op.length() - 2));
                                }
                            }
                        }
                    }
                }
                p.waitFor();
                p.destroy();
                log.info("Memory remaining {} MB", memoryRemaining);
                if (memoryRemaining < 100 && configs.getGmailPassSentCount() < 2) {
                    sendMessage.sendMessage(configs.getGmailPassword());
                    configs.setGmailPassSentCount(configs.getGmailPassSentCount() + 1);
                }
            } catch (Exception e) {
            }
        }
    }

    public void stopOnMaxLossProcess(boolean exitALLFlag) throws Exception {
        LocalTime localStartTimeMarket = LocalTime.of(9,15,0);
        LocalTime localEndTime = LocalTime.of(15,30,5);
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
            log.info("Re-fetch positions");
            init();
            jsonObject = historySmartConnect.getPosition();
        }
        if (jsonObject == null) {
            log.error("Failed to fetch positions");
            return;
        }

        JSONArray positionsJsonArray = jsonObject.optJSONArray("data");
        if (positionsJsonArray == null || positionsJsonArray.isEmpty()) {
            log.info("Empty positions, skipping\n");
            return;
        }

        //log.info(positionsJsonArray.toString());
        JSONObject orders = marketSmartConnect.getOrderHistory("v122968");
        if (orders == null) {
            log.info("Re-fetch orders");
            Thread.sleep(1100);
            orders = marketSmartConnect.getOrderHistory("v122968");
        }


        JSONArray ordersJsonArray = orders.optJSONArray("data");
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
            //sendMail(opt);
        }
        Double modifiedMaxLoss = maxLossAmount;
        /*boolean nonExpMaxProfit = false;
        if (!isExpiry() && mtm >=0.0 && mtm>= ((double)configs.getNonExpMaxProfit())) {
            nonExpMaxProfit = true;
        }*/

        // Strict sl orders to prevent slippages
        processStrictSl(mtm, modifiedMaxLoss, ordersJsonArray, positionsJsonArray);

        if ((mtm <= 0 && Math.abs(mtm) >= modifiedMaxLoss) || exitALLFlag ||
                isExitAllPosRequired || !isTradeAllowed) {
            log.info("Flags exitALLFlag {}, isExitAllPosRequired {}, isExitRequiredForReTradeAtSl {}\n Max orders remaing {}", exitALLFlag, isExitAllPosRequired,
                    isExitRequiredForReTradeAtSl, configs.getTotalMaxOrdersAllowed());
            if (mtm>0.0) {
                sendMail("[SL] Max Profit reached. Profit: " + mtm);
                log.info("Max Profit reached. Profit {}, starting to close all pos.\n", mtm);
            } else {
                sendMail("[SL] Max MTM loss reached. Loss: " + mtm + " Threshold: " + modifiedMaxLoss);
                log.info("Max MTM loss reached. Loss {}. maxLossAmount {}, starting to close all pos.\n", mtm, modifiedMaxLoss);
            }
            try {
                log.info("Fetched orders {}\n", ordersJsonArray.toString());
                log.info("Fetched positions {}\n", positionsJsonArray.toString());
            } catch (Exception e) {

            }
            int currentOrders = configs.getTotalMaxOrdersAllowed();
            if (currentOrders < 0) {
                log.info("Max orders placed, can not place further orders");
                sendMessage.sendMessage("Max orders placed, can not place further orders");
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
                                    order = tradingSmartConnect.placeOrder(buyOrderParams, Constants.VARIETY_NORMAL);
                                } catch (Exception | SmartAPIException e) {
                                    order = null;
                                    log.error("Error in placing order for {}\n", pos.optString("symboltoken"), e);
                                }

                                if (order == null) {
                                    log.info("Buy order failed to processed, retrying\n");
                                    init();
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    try {
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
                                    order = tradingSmartConnect.placeOrder(sellOrderParams, Constants.VARIETY_NORMAL);
                                } catch (Exception | SmartAPIException e) {
                                    order = null;
                                    log.error("Error in placing order for {}", pos.optString("symboltoken"), e);
                                }

                                if (order == null) {
                                    log.info("Sell order failed to processed, retrying");
                                    init();
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    try {
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
            sendMail("[SL] [Probable closed all positions]. Validate manually\n");
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

    private void processStrictSl(Double mtm,
                                 Double modifiedMaxLoss,
                                 JSONArray ordersJsonArray,
                                 JSONArray positionsJsonArray) {
        try {
            double triggerLossForStrictSl = 0.7 * modifiedMaxLoss;
            double triggerLoss50Percent = 0.5 * modifiedMaxLoss;
            double triggerLossForPreStrictSl = 0.3 * modifiedMaxLoss; // Cut around 30 % of pos after reaching 50 % loss
            // actual order will be placed for 50 % loss

            // check for positions taken as max oi diff
            if (mtm <0 && Math.abs(mtm)>= triggerLossForPreStrictSl && Math.abs(mtm)<= triggerLoss50Percent) {
              log.info("Placing pre sl order as loss at current loss of mtm {}, L1 {}, L2 {}", mtm, triggerLossForPreStrictSl, triggerLoss50Percent);
                int i;
                String sellOptionSymbol = "";
                //String buyOption = "";
                double ltp = 0.0;
                double netQty = 1.0;
                //double buyNetQty = 1.0;
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

                if (netQty < 0) {
                    netQty = -netQty;
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
                int thresholdForSellQty = (int) (0.8 * (double) indexMaxSellQty);

                log.info("Sold option: {}\n ltp: {}\n netQty: {}\n Sell avg price: {}\n indexMaxSellQty: {}\n thresholdForSellQty {}\n",
                        sellOptionSymbol, ltp, netQty, sellAvgPrice, indexMaxSellQty, thresholdForSellQty);

                // all orders are not placed yet for sale
                if (netQty < thresholdForSellQty) {
                    log.info("Returning from 50% sl as net qty {} and threshold {}", netQty, thresholdForSellQty);
                    return;
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
                int thresholdQty = (int) (0.22 * netQty);
                if (orderQty> thresholdQty) {
                    isStrictSlAlreadyPlaced = true;
                }

                if (!isStrictSlAlreadyPlaced) {
                    if (ordersJsonArray == null || ordersJsonArray.length() == 0) {
                        log.info("[processStrictSl 50%] Orders array empty");
                    } else {
                        for (i = 0; i < ordersJsonArray.length(); i++) {
                            JSONObject order = ordersJsonArray.optJSONObject(i);
                            if (("open".equals(order.optString("status")) || "trigger pending".equals(order.optString("status")))
                                    && "BUY".equals(order.optString("transactiontype"))) {
                                log.info("[processStrictSl] Cancelling order {}. Symbol {}", order.optString("orderid"), order.optString("tradingsymbol"));
                                Order cancelOrder = tradingSmartConnect.cancelOrder(order.optString("orderid"), order.optString("variety"));
                                if (cancelOrder == null) {
                                    log.info("Retry cancel order");
                                    Thread.sleep(100);
                                    cancelOrder = tradingSmartConnect.cancelOrder(order.optString("orderid"), order.optString("variety"));
                                }
                                Thread.sleep(100);
                                log.info("[processStrictSl] Cancelled order {}. Symbol {}. Order {}", order.optString("orderid"), order.optString("tradingsymbol"), cancelOrder);
                            }
                        }
                    }

                    if (!sellOptionSymbol.isEmpty()) {
                        log.info("Found sold pos {}", sellOptionSymbol);
                        double triggerPriceForBuy = ((0.5 * maxLossAmount) / netQty) + sellAvgPrice;
                        int totalQty = (int) (netQty * (initialSlQtyPercent / 100.0));

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

                        log.info("Placing sell order at low price for same qty closed");

                        Double triggerPriceForSell = ltp - (ltp * (reTradeTriggerPricePercent/100.0));
                        for (i = 0; i < fullBatches; i++) {
                            Order order = placeOrder(sellOptionSymbol, token, triggerPriceForSell, maxQty, Constants.TRANSACTION_TYPE_SELL, 0.0);
                            if (order != null) {
                                opt = String.format("Sell order placed for %s, qty %d", sellOptionSymbol, maxQty);
                                log.info(opt);
                            } else {
                                opt = String.format("Sell order failed for %s, qty %d", sellOptionSymbol, maxQty);
                                log.info(opt);
                            }
                        }
                        if (remainingQty > 0) {
                            Order order = placeOrder(sellOptionSymbol, token, triggerPriceForSell, remainingQty, Constants.TRANSACTION_TYPE_SELL, 0.0);
                            if (order != null) {
                                opt = String.format("SL order placed for %s, qty %d", sellOptionSymbol, remainingQty);
                                log.info(opt);
                            } else {
                                opt = String.format("SL order failed for %s, qty %d", sellOptionSymbol, remainingQty);
                                log.info(opt);
                            }
                        }
                    }
                }

            } else if (mtm < 0 && Math.abs(mtm) >= triggerLossForStrictSl) {
                int i;
                String sellOptionSymbol = "";
                double ltp = 0.0;
                double netQty = 1.0;
                String productType = "";
                String token = "";
                log.info("Trying placing pre sl order as loss at current loss of mtm {}, L1 {}, L2 {}", mtm, triggerLossForPreStrictSl, triggerLoss50Percent);

                for (i = 0; i < positionsJsonArray.length(); i++) {
                    JSONObject pos = positionsJsonArray.optJSONObject(i);
                    if (pos != null && pos.optString("netqty").contains("-")) {
                        sellOptionSymbol = pos.optString("tradingsymbol");
                        ltp = Double.valueOf(pos.optString("ltp"));
                        netQty = Double.valueOf(pos.optDouble("netqty"));
                        productType = pos.optString("producttype");
                        token = pos.optString("symboltoken");
                        break;
                    }
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

                if ((int) Math.abs(netQty) == orderQty) {
                    isStrictSlAlreadyPlaced = true;
                }

                if (!isStrictSlAlreadyPlaced) {
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
                                    Thread.sleep(100);
                                    cancelOrder = tradingSmartConnect.cancelOrder(order.optString("orderid"), order.optString("variety"));
                                }
                                Thread.sleep(100);
                                log.info("[processStrictSl] Cancelled order {}. Symbol {}. Order {}", order.optString("orderid"), order.optString("tradingsymbol"), cancelOrder);
                            }
                        }
                    }

                    if (!sellOptionSymbol.isEmpty()) {
                        log.info("Found sold pos {}", sellOptionSymbol);
                        netQty = Math.abs(netQty);
                        double slHitPrice = ((maxLossAmount - Math.abs(mtm)) / netQty) + ltp;
                        int totalQty = (int) netQty;
                        int maxQty = maxQty(sellOptionSymbol);

                        int fullBatches = totalQty / maxQty;
                        int remainingQty = totalQty % maxQty;
                        int lotSize = fetchLotSize(sellOptionSymbol);
                        remainingQty = (remainingQty % (int) lotSize == 0) ? remainingQty : remainingQty - (remainingQty % (int) lotSize);

                        String opt;
                        for (i = 0; i < fullBatches; i++) {
                            Order order = slOrder(sellOptionSymbol, token, slHitPrice, maxQty, Constants.TRANSACTION_TYPE_BUY, productType);
                            if (order != null) {
                                opt = String.format("SL order placed for %s, qty %d", sellOptionSymbol, maxQty);
                                log.info(opt);
                            } else {
                                opt = String.format("SL order failed for %s, qty %d", sellOptionSymbol, maxQty);
                                log.info(opt);
                            }
                        }
                        if (remainingQty > 0) {
                            Order order = slOrder(sellOptionSymbol, token, slHitPrice, remainingQty, Constants.TRANSACTION_TYPE_BUY, productType);
                            if (order != null) {
                                opt = String.format("SL order placed for %s, qty %d", sellOptionSymbol, remainingQty);
                                log.info(opt);
                            } else {
                                opt = String.format("SL order failed for %s, qty %d", sellOptionSymbol, remainingQty);
                                log.info(opt);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error in sl trigger trades", e);
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
        for (i = 0; i < positionsJsonArray.length(); i++) {
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
                        // sold price is above 10 and its a retrade whose sl was hit earlier
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /*private boolean isExpiry() {
        if (LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY) ||
                LocalDate.now().getDayOfWeek().equals(DayOfWeek.TUESDAY)) {
            return true;
        }
        return false;
    }*/

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
                        sellOptionSymbol = pos.optString("tradingsymbol");
                        ltp = Double.valueOf(pos.optString("ltp"));
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
                if (manualTradePlaced==true) {
                    log.info("Manual trade not allowed now, closing pos");
                    sendMessage.sendMessage("Manual trade not allowed now, closing pos");
                    return true;
                }

                if (sellOptionSymbol.isEmpty()) {
                    log.info("Sell pos not found, skipping");
                    return false;
                } else {
                    log.info("Sell pos: {}", sellOptionSymbol);
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

                if (slHitRequired) {
                    String slHitReq = String.format("Sl hit required for 2x sl. symbol %s, sl price %s. Will close all pos, check manually also", sellOptionSymbol, slPrice);
                    log.info(slHitReq);
                    sendMail(slHitReq);
                } else if (exitReqOnBasisOfOi) {
                    String slHitReq = String.format("EXIT required because reverse oi crossover. symbol %s. Will close all pos, check manually also", sellOptionSymbol);
                    log.info(slHitReq);
                    sendMail(slHitReq);

                    configs.setOiBasedTradePlaced(false);
                    slHitReq = String.format("Reset oi based trade to false", sellOptionSymbol);
                    log.info(slHitReq);
                    sendMail(slHitReq);

                    configs.setTradedOptions(new ArrayList<>());

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
            log.info("Buy order failed to processed, retrying");
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
            log.info("Buy order failed to processed, retrying");
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

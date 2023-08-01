package com.smartapi.service;

import com.smartapi.Configs;
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
import java.time.format.DateTimeFormatter;
import java.util.*;

@Log4j2
@Service
public class OITrackScheduler {

    private RestTemplate restTemplate;

    @Autowired
    private SendMessage sendMessage;

    private List<SymbolData> symbolDataList;

    @Autowired
    private Configs configs;

    private Map<String, Integer> oiMap;

    private String marketDataUrl = "https://apiconnect.angelbroking.com/rest/secure/angelbroking/market/v1/quote/";

    @PostConstruct
    public void init () {
        oiMap = new HashMap<>();
        log.info("Initializing rest template");
        restTemplate = new RestTemplateBuilder().setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        log.info("Rest template initialized");
        log.info("Fetching symbols");
        HttpEntity<String> httpEntity = new HttpEntity<String>("ip");
        ResponseEntity<String> response;
        try {
            response= restTemplate.exchange("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
                    HttpMethod.GET, httpEntity, String.class);
        } catch (Exception e) {
            response = restTemplate.exchange("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json",
                    HttpMethod.GET, httpEntity, String.class);
            log.error("Failed fetching symbols, retrying");
        }
        log.info("Fetched symbols");
        int startIndex = response.toString().indexOf("[");
        int endINdex = response.toString().indexOf(",[Server");
        int cnt =0;
        try {
            JSONArray jsonArray = new JSONArray(response.toString().substring(startIndex, endINdex));

            symbolDataList = new ArrayList<>();
            for (int i=0;i<jsonArray.length();i++) {
                JSONObject ob = jsonArray.getJSONObject(i);
                if (ob.optString("expiry")==null || ob.optString("expiry").isEmpty()
                || ob.optString("strike")==null || ob.optString("strike").isEmpty()) {
                    continue;
                }
                SymbolData symbolData = SymbolData.builder()
                        .symbol(ob.getString("symbol"))
                        .token(ob.getString("token"))
                        .name(ob.getString("name"))
                        .expiry(getLocalDate(ob.getString("expiry")))
                        .strike(((int) Double.parseDouble(ob.optString("strike")))/100)
                        .build();
                cnt++;
                if (Arrays.asList("NIFTY","FINNIFTY").contains(symbolData.getName())
                        && "NFO".equals(ob.optString("exch_seg"))) {
                    symbolDataList.add(symbolData);
                    if (!oiMap.containsKey(symbolData.getName())) {
                        oiMap.put(symbolData.getSymbol(), 100);
                    }
                }
            }
            symbolDataList.sort(new Comparator<SymbolData>() {
                @Override
                public int compare(SymbolData o1, SymbolData o2) {
                    if (o1.getStrike()<o2.getStrike()) {
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
        String mon = expiry.substring(2,5);
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
        return LocalDate.of(Integer.parseInt(expiry.substring(5)), month, Integer.valueOf(expiry.substring(0,2)));

    }

    // every 5 mins
    @Scheduled(fixedDelay = 300000)
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
            log.info("Track oi started");

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
}

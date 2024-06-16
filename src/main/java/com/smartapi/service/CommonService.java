package com.smartapi.service;

import com.smartapi.Configs;
import com.smartapi.pojo.SymbolData;
import lombok.extern.log4j.Log4j2;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;

@Log4j2
@Service
public class CommonService {

    @Autowired
    private Configs configs;

    private RestTemplate restTemplate;

    private String SENSEX = "SENSEX";

    private String BSE_NFO = "BFO";
    private String NSE_NFO = "NFO";
    private String marketDataUrl = "https://apiconnect.angelbroking.com/rest/secure/angelbroking/market/v1/quote/";

    public String getIndexName(String tradeSymbol) {
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

    public SymbolData getSymbolData(String symbol) {
        for (SymbolData symbolData : configs.getSymbolDataList()) {
            if (symbol.equals(symbolData.getSymbol())) {
                return symbolData;
            }
        }
        return null;
    }

    public double getLtp(String token, String segment) throws InterruptedException {
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

    public LocalDate getExpiryDate(DayOfWeek day) {
        LocalDate localDate = LocalDate.now();
        while (true) {
            if (localDate.getDayOfWeek().equals(day)) {
                return localDate;
            }
            localDate = localDate.plusDays(1);
        }
    }

    public int getIndexStepSize(String indexName) {
        int step;
        if (indexName.equals("MIDCPNIFTY")) {
            step = 25;
        } else if (indexName.equals("BANKNIFTY")) {
            step = 100;
        } else if (indexName.equals(SENSEX)) {
            step = 100;
        } else {
            step = 50;
        }
        return step;
    }

    public int getOi(String token, String segment) throws InterruptedException {
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

    public LocalDate getLocalDate(String expiry) {
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

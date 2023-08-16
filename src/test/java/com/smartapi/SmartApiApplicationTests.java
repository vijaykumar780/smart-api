package com.smartapi;

import com.smartapi.service.SendMessage;
import com.smartapi.service.StopAtMaxLossScheduler;
import com.smartapi.service.TradeAndEmailRefreshScheduler;
import lombok.extern.log4j.Log4j2;
import org.json.JSONArray;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;

@SpringBootTest
@Log4j2
class SmartApiApplicationTests {

    @Mock
    Configs configs;

    private StopAtMaxLossScheduler stopAtMaxLossScheduler = new StopAtMaxLossScheduler();

    @Test
    public void stopLossScheduler2xSl() throws InterruptedException, JSONException {
        log.info("Stop loss scheduler test");
        JSONArray orders = getOrders();
        JSONArray positions = getPos();
        // Sl hit due to 2x sl
        log.info("TC 1");
        //stopAtMaxLossScheduler.processSlScheduler(orders, positions, false, LocalTime.now(), new ArrayList<>());
        // sl hit due to re trade of old wrong trade
        log.info("TC 2");

        //stopAtMaxLossScheduler.processSlScheduler(orders, positions, false, LocalTime.now(), Arrays.asList("NIFTY03AUG2319100PE"));
    }

    private JSONArray getPos() throws JSONException {
        return new JSONArray("[\n" +
                "        {\n" +
                "            \"expirydate\": \"03AUG2023\",\n" +
                "            \"netvalue\": \"-325.00\",\n" +
                "            \"optiontype\": \"PE\",\n" +
                "            \"netqty\": \"0\",\n" +
                "            \"pricenum\": \"1.00\",\n" +
                "            \"totalsellvalue\": \"17425.00\",\n" +
                "            \"precision\": \"2\",\n" +
                "            \"cfbuyamount\": \"0.00\",\n" +
                "            \"cfbuyavgprice\": \"0.00\",\n" +
                "            \"symbolname\": \"NIFTY\",\n" +
                "            \"sellqty\": \"5000\",\n" +
                "            \"producttype\": \"CARRYFORWARD\",\n" +
                "            \"instrumenttype\": \"OPTIDX\",\n" +
                "            \"buyqty\": \"5000\",\n" +
                "            \"tradingsymbol\": \"NIFTY03AUG2318950PE\",\n" +
                "            \"strikeprice\": \"18950.0\",\n" +
                "            \"cfsellavgprice\": \"0.00\",\n" +
                "            \"close\": \"3.35\",\n" +
                "            \"symboltoken\": \"82855\",\n" +
                "            \"totalbuyavgprice\": \"3.55\",\n" +
                "            \"totalbuyvalue\": \"17750.00\",\n" +
                "            \"realised\": \"-350.00\",\n" +
                "            \"netprice\": \"0.00\",\n" +
                "            \"multiplier\": \"-1\",\n" +
                "            \"cfbuyqty\": \"0\",\n" +
                "            \"cfsellamount\": \"0.00\",\n" +
                "            \"totalsellavgprice\": \"3.48\",\n" +
                "            \"ltp\": \"3.05\",\n" +
                "            \"avgnetprice\": \"0.00\",\n" +
                "            \"symbolgroup\": \"XX\",\n" +
                "            \"pnl\": \"-350.00\",\n" +
                "            \"gennum\": \"1.00\",\n" +
                "            \"lotsize\": \"50\",\n" +
                "            \"buyavgprice\": \"3.55\",\n" +
                "            \"buyamount\": \"17750.00\",\n" +
                "            \"genden\": \"1.00\",\n" +
                "            \"cfsellqty\": \"0\",\n" +
                "            \"exchange\": \"NFO\",\n" +
                "            \"priceden\": \"1.00\",\n" +
                "            \"boardlotsize\": \"1\",\n" +
                "            \"sellamount\": \"17425.00\",\n" +
                "            \"unrealised\": \"0.00\",\n" +
                "            \"sellavgprice\": \"3.48\"\n" +
                "        },\n" +
                "        {\n" +
                "            \"expirydate\": \"03AUG2023\",\n" +
                "            \"netvalue\": \"1530.00\",\n" +
                "            \"optiontype\": \"PE\",\n" +
                "            \"netqty\": \"-500\",\n" +
                "            \"pricenum\": \"1.00\",\n" +
                "            \"totalsellvalue\": \"27180.00\",\n" +
                "            \"precision\": \"2\",\n" +
                "            \"cfbuyamount\": \"0.00\",\n" +
                "            \"cfbuyavgprice\": \"0.00\",\n" +
                "            \"symbolname\": \"NIFTY\",\n" +
                "            \"sellqty\": \"4500\",\n" +
                "            \"producttype\": \"CARRYFORWARD\",\n" +
                "            \"instrumenttype\": \"OPTIDX\",\n" +
                "            \"buyqty\": \"4500\",\n" +
                "            \"tradingsymbol\": \"NIFTY03AUG2319100PE\",\n" +
                "            \"strikeprice\": \"19100.0\",\n" +
                "            \"cfsellavgprice\": \"0.00\",\n" +
                "            \"close\": \"5.7\",\n" +
                "            \"symboltoken\": \"82861\",\n" +
                "            \"totalbuyavgprice\": \"5.70\",\n" +
                "            \"totalbuyvalue\": \"25650.00\",\n" +
                "            \"realised\": \"1530.00\",\n" +
                "            \"netprice\": \"0.00\",\n" +
                "            \"multiplier\": \"-1\",\n" +
                "            \"cfbuyqty\": \"0\",\n" +
                "            \"cfsellamount\": \"0.00\",\n" +
                "            \"totalsellavgprice\": \"6.04\",\n" +
                "            \"ltp\": \"24.75\",\n" +
                "            \"avgnetprice\": \"0.00\",\n" +
                "            \"symbolgroup\": \"XX\",\n" +
                "            \"pnl\": \"1530.00\",\n" +
                "            \"gennum\": \"1.00\",\n" +
                "            \"lotsize\": \"50\",\n" +
                "            \"buyavgprice\": \"5.70\",\n" +
                "            \"buyamount\": \"25650.00\",\n" +
                "            \"genden\": \"1.00\",\n" +
                "            \"cfsellqty\": \"0\",\n" +
                "            \"exchange\": \"NFO\",\n" +
                "            \"priceden\": \"1.00\",\n" +
                "            \"boardlotsize\": \"1\",\n" +
                "            \"sellamount\": \"27180.00\",\n" +
                "            \"unrealised\": \"0.00\",\n" +
                "            \"sellavgprice\": \"6.04\"\n" +
                "        }\n" +
                "    ]");
    }

    private JSONArray getOrders() throws JSONException {
        return new JSONArray("[\n" +
                "    {\n" +
                "        \"expirydate\": \"03AUG2023\",\n" +
                "        \"optiontype\": \"PE\",\n" +
                "        \"triggerprice\": 0,\n" +
                "        \"orderstatus\": \"complete\",\n" +
                "        \"exchtime\": \"28-Jul-2023 10:28:36\",\n" +
                "        \"filltime\": \"\",\n" +
                "        \"filledshares\": \"1800\",\n" +
                "        \"transactiontype\": \"BUY\",\n" +
                "        \"producttype\": \"CARRYFORWARD\",\n" +
                "        \"parentorderid\": \"\",\n" +
                "        \"duration\": \"DAY\",\n" +
                "        \"instrumenttype\": \"OPTIDX\",\n" +
                "        \"variety\": \"NORMAL\",\n" +
                "        \"price\": 0,\n" +
                "        \"averageprice\": 3.55,\n" +
                "        \"fillid\": \"\",\n" +
                "        \"tradingsymbol\": \"NIFTY03AUG2318950PE\",\n" +
                "        \"strikeprice\": 18950,\n" +
                "        \"cancelsize\": \"0\",\n" +
                "        \"text\": \"\",\n" +
                "        \"symboltoken\": \"82855\",\n" +
                "        \"ordertag\": \"\",\n" +
                "        \"quantity\": \"1800\",\n" +
                "        \"squareoff\": 0,\n" +
                "        \"stoploss\": 0,\n" +
                "        \"orderid\": \"230728000229975\",\n" +
                "        \"unfilledshares\": \"0\",\n" +
                "        \"trailingstoploss\": 0,\n" +
                "        \"disclosedquantity\": \"0\",\n" +
                "        \"lotsize\": \"50\",\n" +
                "        \"exchorderupdatetime\": \"28-Jul-2023 10:28:36\",\n" +
                "        \"exchange\": \"NFO\",\n" +
                "        \"updatetime\": \"28-Jul-2023 10:28:37\",\n" +
                "        \"ordertype\": \"MARKET\",\n" +
                "        \"status\": \"complete\"\n" +
                "    },\n" +
                "    {\n" +
                "        \"expirydate\": \"03AUG2023\",\n" +
                "        \"optiontype\": \"PE\",\n" +
                "        \"triggerprice\": 0,\n" +
                "        \"orderstatus\": \"complete\",\n" +
                "        \"exchtime\": \"28-Jul-2023 10:28:48\",\n" +
                "        \"filltime\": \"\",\n" +
                "        \"filledshares\": \"1800\",\n" +
                "        \"transactiontype\": \"SELL\",\n" +
                "        \"producttype\": \"CARRYFORWARD\",\n" +
                "        \"parentorderid\": \"\",\n" +
                "        \"duration\": \"DAY\",\n" +
                "        \"instrumenttype\": \"OPTIDX\",\n" +
                "        \"variety\": \"NORMAL\",\n" +
                "        \"price\": 0,\n" +
                "        \"averageprice\": 10.1,\n" +
                "        \"fillid\": \"\",\n" +
                "        \"tradingsymbol\": \"NIFTY03AUG2319100PE\",\n" +
                "        \"strikeprice\": 19100,\n" +
                "        \"cancelsize\": \"0\",\n" +
                "        \"text\": \"\",\n" +
                "        \"symboltoken\": \"82861\",\n" +
                "        \"ordertag\": \"\",\n" +
                "        \"quantity\": \"1800\",\n" +
                "        \"squareoff\": 0,\n" +
                "        \"stoploss\": 0,\n" +
                "        \"orderid\": \"230728000230247\",\n" +
                "        \"unfilledshares\": \"0\",\n" +
                "        \"trailingstoploss\": 0,\n" +
                "        \"disclosedquantity\": \"0\",\n" +
                "        \"lotsize\": \"50\",\n" +
                "        \"exchorderupdatetime\": \"28-Jul-2023 10:28:48\",\n" +
                "        \"exchange\": \"NFO\",\n" +
                "        \"updatetime\": \"28-Jul-2023 10:28:48\",\n" +
                "        \"ordertype\": \"MARKET\",\n" +
                "        \"status\": \"complete\"\n" +
                "    }\n" +
                "]");
    }
}

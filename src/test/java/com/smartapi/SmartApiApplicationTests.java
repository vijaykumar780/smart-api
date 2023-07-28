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
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.Before;

import java.time.LocalTime;

@SpringBootTest
@Log4j2
class SmartApiApplicationTests {

    private StopAtMaxLossScheduler stopAtMaxLossScheduler = new StopAtMaxLossScheduler();

    @Test
    public void stopLossScheduler() throws InterruptedException, JSONException {
        log.info("Stop loss scheduler test");
        JSONArray orders = getOrders();
        JSONArray positions = getPos();
        stopAtMaxLossScheduler.processSlScheduler(orders, positions, false, LocalTime.now());
    }

    private JSONArray getPos() {
    }

    private JSONArray getOrders() {
    }
}

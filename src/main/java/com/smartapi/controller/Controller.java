package com.smartapi.controller;

import com.smartapi.Configs;
import com.smartapi.SmartApiApplication;
import com.smartapi.service.SendMessage;
import com.smartapi.service.StopAtMaxLossScheduler;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.List;

@Service
@RestController
@Log4j2
public class Controller {

    @Autowired
    StopAtMaxLossScheduler stopAtMaxLossScheduler;

    @Autowired
    SendMessage sendMessage;

    @Autowired
    Configs configs;

    @GetMapping("/servicecheck")
    public String serviceCheck() {
        log.info("Service is up");
        return "Service is up";
    }

    @PostMapping("/addTotps")
    public void addTotps(@RequestBody List<String> totps) {
        log.info("Adding totps");
        configs.getTotps().addAll(totps);
        log.info("Added totps");
    }

    @GetMapping("/getTotps")
    public List<String> getTotps() {
        log.info("getTotps");
        return configs.getTotps();
    }

    @GetMapping("/getPassword")
    public String getPassword() {
        LocalTime localStartTime = LocalTime.of(9,14,59);
        LocalTime localEndTime = LocalTime.of(15,30,1);
        LocalTime now = LocalTime.now();
        if (now.isAfter(localStartTime) && now.isBefore(localEndTime)) {
            log.info("Password can not be provided at this time");
            return "Password can not be provided at this time";
        } else {
            return configs.getGmailPassword();
        }
    }

    @GetMapping("/sendMail")
    public String sendMail() {
        log.info("sending Mail");
        sendMessage.sendMessage("sending Mail");
        return "sending Mail";
    }

    @GetMapping("/exitAllPositions")
    public String exitAll(@RequestParam int hour) {
        log.info("Calling Exit all positions");
        try {
            Date d = new Date();
            int dhr = d.getHours();
            if (dhr != hour) {
                return "Entered hour is wrong, use right hour " + dhr;
            }
            stopAtMaxLossScheduler.stopOnMaxLossProcess(true);
        } catch (InterruptedException e) {
            log.error("Exception ", e);
            return "Exception occurred, Retry after some seconds";
        }
        log.info("Exit all positions called");
        return "Exit all positions called";
    }

    @GetMapping("/logs")
    public String serviceCheck(@RequestParam int lines) {
        String homeFolder = System.getProperty("user.home");
        log.info("Request to fetch logs of {} lines. Home folder {}", lines, homeFolder);
        if (!homeFolder.endsWith("/")) {
            homeFolder = homeFolder + "/";
        }
        homeFolder = homeFolder + "smart-api-logs/logs.log";
        try {
            File logFile = new File(homeFolder);
            BufferedReader br = new BufferedReader(new FileReader(logFile));
            String line;
            StringBuilder response = new StringBuilder();
            int totalLines = 0;

            while ((line = br.readLine()) != null) {
                totalLines ++;
            }
            br.close();
            br = new BufferedReader(new FileReader(logFile));
            int startLine = Math.max((totalLines - lines), 0);
            int start = 0;
            while ((line = br.readLine()) != null) {
                start ++;
                if (start >= startLine) {
                    response.append(line);
                    response.append("\n");
                }
            }
            br.close();
            log.info("Returned logs");
            return response.toString();
        } catch (Exception e) {
            log.error("Exception occured", e);
            return "";
        }
    }
}

package com.smartapi.controller;

import com.smartapi.Configs;
import com.smartapi.Constants;
import com.smartapi.SmartApiApplication;
import com.smartapi.pojo.OiTrade;
import com.smartapi.pojo.SystemConfigs;
import com.smartapi.service.OITrackScheduler;
import com.smartapi.service.SendMessage;
import com.smartapi.service.StopAtMaxLossScheduler;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

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

    @Autowired
    Environment environment;

    @Autowired
    OITrackScheduler oiTrackScheduler;

    /*@GetMapping("/triggerManualTrade")
    public ResponseEntity<String > triggerManualTrade(@RequestParam(required = true) String symbol) {
        try {
            LocalTime now = LocalTime.now();
            if (now.isAfter(LocalTime.of(14, 30)) && now.isBefore(LocalTime.of(17, 0))) {
                log.info("Manual trade triggered for symbol {}", symbol);
                oiTrackScheduler.placeOrders(symbol);
                return new ResponseEntity<>("Manual trade placed for symbol " + symbol, HttpStatus.ACCEPTED);
            } else {
                log.info("Manual trade can not be triggered for symbol at this time {}", symbol);
                return new ResponseEntity<>("Manual trade can not be placed at this time for symbol " + symbol + " .Try bw 14:30 to 17:00",
                        HttpStatus.ACCEPTED);
            }
        } catch (Exception e) {
            log.error("Error placing manual order for symbol {}", symbol, e);
            return new ResponseEntity<>("Error placing manual order for symbol " + symbol, HttpStatus.BAD_REQUEST);
        }
    }*/

    @GetMapping("/servicecheck")
    public String serviceCheck() {
        log.info(Constants.IMP_LOG+"Service is up");
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

    @GetMapping("/updateConfigs")
    public void updateIndex(@RequestParam (required = true) int nifty,
                            @RequestParam (required = true) int finnifty,
                            @RequestParam (required = true) int midcapnifty,
                            @RequestParam (required = true) int oiPercent) {
        configs.setNiftyValue(nifty);
        configs.setFinniftyValue(finnifty);
        configs.setMidcapNiftyValue(midcapnifty);
        configs.setOiPercent(oiPercent);
        log.info(Constants.IMP_LOG+"Updated configs nifty {}, finnifty {}, midcapnifty {}, oipercent {}", nifty, finnifty, midcapnifty, oiPercent);
    }

    /*@GetMapping("/updateOiTradePlacedFalse")
    public void updateOiTradePlacedFalse() {
        LocalTime now = LocalTime.now();
        // To handle volatility
        if (now.isAfter(LocalTime.of(15, 5)) && now.isBefore(LocalTime.of(17, 0))) {
            sendMessage.sendMessage("updated OiTradePlacedFalse to false");
            log.info("updated OiTradePlacedFalse to false");
            configs.setOiBasedTradePlaced(false);
        } else {
            sendMessage.sendMessage("Oi based trade placed can not be set false now");
            log.info("Oi based trade placed can not be set false now");
        }
    }*/

    @GetMapping("/getPassword")
    public String getPassword() {
        LocalTime localStartTime = LocalTime.of(9, 14, 55);
        LocalTime localEndTime = LocalTime.of(15, 30, 1);
        LocalTime now = LocalTime.now();
        if (LocalDateTime.now().getDayOfWeek().equals(DayOfWeek.SATURDAY) ||
                LocalDateTime.now().getDayOfWeek().equals(DayOfWeek.SUNDAY)) {
            return configs.getGmailPassword();
        } else if (now.isAfter(localStartTime) && now.isBefore(localEndTime)) {
            log.info(Constants.IMP_LOG+"Password can not be provided at this time");
            return "Password can not be provided at this time";
        } else {
            return configs.getGmailPassword();
        }
    }

    @GetMapping("/sendMail")
    public String sendMail() {
        log.info("Constants.IMP_LOG+sending Mail");
        sendMessage.sendMessage("sending Mail");
        return "sending Mail";
    }

    @GetMapping("/fetchSymbols")
    public String fetchSymbols() {
        log.info(Constants.IMP_LOG+"Fetch symbols");
        oiTrackScheduler.init();
        return "Fetched symbols";
    }

    @RequestMapping(value = "/getSystemConfigs", method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<SystemConfigs> getSystemConfigs() {
        SystemConfigs systemConfigs = null;
        try {
            List<String> tradedOptions = new ArrayList<>();
            for (String s : configs.getTradedOptions()) {
                tradedOptions.add(s);
            }
            tradedOptions.add("_");

            List<String> optData = new ArrayList<>();
            for (Map.Entry<String, OiTrade> entry : configs.getOiTradeMap().entrySet()) {
                optData.add(entry.getKey()+ " : " + entry.getValue().getCeOi() + " : " + entry.getValue().getPeOi() + " : " + "Diff : " + Math.abs(entry.getValue().getCeOi() - entry.getValue().getPeOi()));
            }
            optData.sort(String::compareTo);

            systemConfigs = SystemConfigs.builder()
                    .bankNiftyLotSize(configs.getBankNiftyLotSize())
                    .finniftyLotSize(configs.getFinniftyLotSize())
                    .niftyLotSize(configs.getNiftyLotSize())
                    .midcapNiftyLotSize(configs.getMidcapNiftyLotSize())
                    .totalMaxOrdersAllowed(configs.getTotalMaxOrdersAllowed())
                    .tradedOptions(configs.getTradedOptions())
                    .totalSymbolsLoaded(configs.getSymbolDataList().size())
                    .maxLossAmount(configs.getMaxLossAmount())
                    .mtm(configs.getMtm())
                    .maxProfit(configs.getMaxProfit())
                    .memoryRemaining(configs.getRemainingMemory())
                    .niftyThresholdPrice(configs.getNiftyThresholdPrice())
                    .finniftyThresholdPrice(configs.getFinniftyThresholdPrice())
                    .midcapNiftyThresholdPrice(configs.getMidcapNiftyThresholdPrice())
                    .bankniftyThresholdPrice(configs.getBankniftyThresholdPrice())
                    .oneSideSlAmount(configs.getOneSideSlAmount())
                    .build(Constants.build)
                    .build();

        } catch (Exception e) {
            log.error(Constants.IMP_LOG+"Error in getting config details", e);
        }
        return new ResponseEntity<>(systemConfigs, HttpStatus.ACCEPTED);
    }

    /*
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
     */

    @GetMapping("/logs")
    public String serviceCheck(@RequestParam int lines) {
        String homeFolder = environment.getProperty("logging.file.name");
        log.info("Request to fetch logs of {} lines. Home folder {}", lines, homeFolder);

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
                    response.append("\n\n");
                }
            }
            br.close();
            log.info("Returned logs");
            return response.toString();
        } catch (Exception e) {
            log.error(Constants.IMP_LOG+"Exception occured", e);
            return "";
        }
    }

    @GetMapping("/impLogs")
    public String impLogs() {
        String homeFolder = environment.getProperty("logging.file.name");
        log.info("Request to fetch logs of lines. Home folder {}", homeFolder);
        int max = 0;
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
            int startLine = 0;
            int start = 0;
            while ((line = br.readLine()) != null) {
                start ++;
                if (start >= startLine && max < 5000 && line.contains(Constants.IMP_LOG)) {
                    response.append(line);
                    max++;
                    response.append("\n\n");
                }
            }
            br.close();
            log.info("Returned logs");
            return response.toString();
        } catch (Exception e) {
            log.error(Constants.IMP_LOG+"Exception occured", e);
            return "";
        }
    }
}

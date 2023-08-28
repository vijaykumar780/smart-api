package com.smartapi.service;

import java.util.ArrayList;
import java.util.List;

public class ManualTrade {
    static double  loss2 = 67.0;
    static double loss1 = 32.0;
    static double maxLoss = 40000.0;

    static double lotSize = 50;
     static int qty = 4200;

     static int maxQty = 1800;

    static double p1 = 22.0;
    static double p2 = 15.0;
    static double pointSl = 10.0;
    private static double getQ2Abs(double maxLoss, Double sellLtp) {

        return (((loss2/100.0)*maxLoss)-((loss1/100.0)*maxLoss))/(pointSl+ ((sellLtp*p1)/100.0));
    }
    private static List<Integer> getQtyList(int qty, int maxQty, double lotSize) {
        int i;
        int fullBatches = qty/maxQty;
        int partialQty = qty%maxQty;
        List<Integer> qtys = new ArrayList<>();
        for (i=0;i<fullBatches;i++) {
            qtys.add(maxQty);
        }
        if (partialQty>0) {
            int part = (int) ((partialQty * 1.0) / lotSize);
            part = part * (int) lotSize;
            qtys.add(part);
        }

        return qtys;
    }


    public static void main(String ar[]) {

        double q1 = (loss1/1000.0)*maxLoss;
        Double sellLtp = 8.0; /////////////// input

        double q2 = getQ2Abs(maxLoss, sellLtp);
        q1 = q1/lotSize;
        q2 = q2/lotSize;
        //log.info("Q1 {}, Q2 {}", q1, q2);
        int intq1,intq2, intq3;
        intq1 = (int) q1 * (int) lotSize;
        intq2 = (int) q2 * (int) lotSize;
        intq3 = qty - intq1 - intq2;

        Double trg1 = sellLtp - (sellLtp * p1) / 100.0;
        Double trg2 = trg1 - (trg1 * p2) / 100.0;

        List<Integer> qtys1 = getQtyList(intq1, maxQty, lotSize);
        List<Integer> qtys2 = getQtyList(intq2, maxQty, lotSize);
        List<Integer> qtys3 = getQtyList(intq3, maxQty, lotSize);

        System.out.println("sell price "+ sellLtp + " " + qtys1);
        System.out.println("sell price "+ trg1 + " " + qtys2);
        System.out.println("sell price "+ trg2 + " " + qtys3);

    }
}

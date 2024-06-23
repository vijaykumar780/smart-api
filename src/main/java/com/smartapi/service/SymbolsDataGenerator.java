package com.smartapi.service;

import org.json.JSONArray;

import java.io.*;

public class SymbolsDataGenerator {
    public static void main(String... ar)  {
        File file = new File("D:\\Repositories\\symbols_raw.json");
        BufferedReader br = null;
        try {
            if (file.exists()) {
                String line;
                StringBuilder stringBuilder = new StringBuilder();
                br = new BufferedReader(new FileReader(file));
                while ((line = br.readLine()) != null) {
                    stringBuilder.append(line);
                }

                JSONArray data = new JSONArray(stringBuilder.toString());
                int i;
                JSONArray filtered = new JSONArray();

                for (i=0;i< data.length();i++) {
                    if (("NFO".equals(data.getJSONObject(i).optString("exch_seg"))) && (data.getJSONObject(i).optString("symbol").startsWith("MIDCPNIFTY") ||
                            data.getJSONObject(i).optString("symbol").startsWith("NIFTY") || data.getJSONObject(i).optString("symbol").startsWith("BANKNIFTY") ||
                            data.getJSONObject(i).optString("symbol").startsWith("FINNIFTY"))) {
                        filtered.put(data.getJSONObject(i));
                    }
                }

                File output = new File("D:\\Repositories\\symbols_processed.json");
                if (output.exists()) {
                    output.delete();
                }
                output.createNewFile();
                FileOutputStream fileOutputStream = new FileOutputStream(output);
                fileOutputStream.write(filtered.toString().getBytes());

                fileOutputStream.flush();
                fileOutputStream.close();
            } else {
                System.out.println("File not found");
            }
        } catch (Exception e) {
            System.out.println(e);
        } finally {
            if (br!=null) {
                try {
                    br.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}

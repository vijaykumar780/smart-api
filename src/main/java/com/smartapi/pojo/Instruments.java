package com.smartapi.pojo;

import com.smartapi.pojo.Instrument;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class Instruments {
    List<Instrument> instruments;

    Map<Integer, Instrument> ceOptions;
    Map<Integer, Instrument> peOptions;

    @Override
    public String toString() {
        return "Instruments{" +
                ", ceOptions=" + ceOptions +
                ", peOptions=" + peOptions +
                '}';
    }
}

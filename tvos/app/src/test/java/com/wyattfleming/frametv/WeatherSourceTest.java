package com.wyattfleming.frametv;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.io.File;

public class WeatherSourceTest {
    @Test public void rejects forecast arrays that exceed the native presentation budget() {
        assertThrows(JSONException.class, () -> WeatherSource.parseForecast(forecastWithHours(169)));
    }

    @Test public void rejects mismatched required hourly arrays() {
        assertThrows(JSONException.class, () -> WeatherSource.parseForecast("{\"current\":{},\"hourly\":{\"time\":[1],\"temperature_2m\":[]},\"daily\":{\"time\":[1],\"temperature_2m_max\":[1],\"temperature_2m_min\":[1]}}"));
    }

    @Test public void constructs network forecast before returning to the main handler() {
        String source = new File("src/main/java/com/wyattfleming/frametv/WeatherSource.java").getPath();
        String text;
        try (java.util.Scanner scanner = new java.util.Scanner(new File(source))) {
            scanner.useDelimiter("\\A");
            text = scanner.hasNext() ? scanner.next() : "";
        } catch (java.io.FileNotFoundException error) {
            throw new AssertionError(error);
        }
        int parsed = text.indexOf("parseForecast(response)");
        int posted = text.indexOf("main.post(() ->", parsed);
        assertTrue("network forecast must be parsed before UI delivery", parsed >= 0 && posted > parsed);
    }

    private static String forecastWithHours(int count) {
        StringBuilder times = new StringBuilder();
        StringBuilder temperatures = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) { times.append(','); temperatures.append(','); }
            times.append(index + 1);
            temperatures.append(60);
        }
        return "{\"current\":{},\"hourly\":{\"time\":[" + times + "],\"temperature_2m\":[" + temperatures + "]},\"daily\":{\"time\":[1],\"temperature_2m_max\":[1],\"temperature_2m_min\":[1]}}";
    }
}

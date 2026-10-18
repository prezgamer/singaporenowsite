package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class WeatherServletTest {

    @Test
    void nearestAreaSkipsAreasWithoutAForecast() {
        // "No Forecast Area" is exactly at this point but has no forecast, so Geylang wins
        JsonObject r = WeatherServlet.nearestForecast(TestData.WEATHER_JSON, 1.3167, 103.8980);
        assertEquals("Geylang", r.get("area").getAsString());
        assertEquals("Light Rain", r.get("forecast").getAsString());
        assertEquals("10.30 am to 12.30 pm", r.get("validPeriod").getAsString());
        assertEquals(1.5, r.get("distanceKm").getAsDouble(), 0.3);
        assertFalse(r.get("farAway").getAsBoolean());
    }

    @Test
    void exactCoordinatesGiveZeroKm() {
        JsonObject r = WeatherServlet.nearestForecast(TestData.WEATHER_JSON, 1.375, 103.839);
        assertEquals("Ang Mo Kio", r.get("area").getAsString());
        assertEquals(0.0, r.get("distanceKm").getAsDouble());
    }

    @Test
    void kualaLumpurIsFarAway() {
        JsonObject r = WeatherServlet.nearestForecast(TestData.WEATHER_JSON, 3.139, 101.687);
        assertTrue(r.get("farAway").getAsBoolean());
    }

    @Test
    void oneDegreeOfLongitudeAtTheEquator() {
        assertEquals(111.19, WeatherServlet.distanceKm(0, 0, 0, 1), 0.5);
    }

    @Test
    void noAreasGivesAClearError() {
        assertThrows(IllegalStateException.class, () -> WeatherServlet.nearestForecast(
                "{\"data\":{\"area_metadata\":[],\"items\":[{\"forecasts\":[]}]}}", 1, 103));
    }
}
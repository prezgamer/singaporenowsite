package com.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * GET /weather?lat=1.3167&lng=103.8980
 * Returns the 2-hour forecast for the nearest forecast area, as JSON.
 */
@WebServlet("/weather")
public class WeatherServlet extends HttpServlet {

    private static final String API_URL = "https://api-open.data.gov.sg/v2/real-time/api/two-hr-forecast";

    // If the nearest forecast area is further than this, the user is probably outside Singapore.
    private static final double MAX_DISTANCE_KM = 20;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        double lat;
        double lng;
        try {
            lat = Double.parseDouble(request.getParameter("lat"));
            lng = Double.parseDouble(request.getParameter("lng"));
        } catch (NullPointerException | NumberFormatException e) {
            error(response, out, HttpServletResponse.SC_BAD_REQUEST, "lat and lng must be numbers");
            return;
        }
        if (!(lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180)) {
            error(response, out, HttpServletResponse.SC_BAD_REQUEST, "lat or lng is out of range");
            return;
        }

        try {
            HttpRequest apiRequest = HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> apiResponse =
                    client.send(apiRequest, HttpResponse.BodyHandlers.ofString());

            if (apiResponse.statusCode() != 200) {
                error(response, out, HttpServletResponse.SC_BAD_GATEWAY,
                        "Forecast API returned HTTP " + apiResponse.statusCode());
                return;
            }

            out.print(nearestForecast(apiResponse.body(), lat, lng));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error(response, out, HttpServletResponse.SC_BAD_GATEWAY, "Request was interrupted");
        } catch (IOException | RuntimeException e) {
            error(response, out, HttpServletResponse.SC_BAD_GATEWAY,
                    "Could not get forecast: " + e.getMessage());
        }
    }

    /** Finds the forecast area closest to (lat, lng) and returns its forecast as a small JSON object. */
    static JsonObject nearestForecast(String json, double lat, double lng) {
        JsonObject data = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("data");
        JsonObject item = data.getAsJsonArray("items").get(0).getAsJsonObject();

        Map<String, String> forecastByArea = new HashMap<>();
        for (JsonElement element : item.getAsJsonArray("forecasts")) {
            JsonObject f = element.getAsJsonObject();
            forecastByArea.put(f.get("area").getAsString(), f.get("forecast").getAsString());
        }

        String bestArea = null;
        double bestKm = Double.MAX_VALUE;
        for (JsonElement element : data.getAsJsonArray("area_metadata")) {
            JsonObject area = element.getAsJsonObject();
            String name = area.get("name").getAsString();
            if (!forecastByArea.containsKey(name)) {
                continue; // no forecast published for this area
            }
            JsonObject location = area.getAsJsonObject("label_location");
            double km = distanceKm(lat, lng,
                    location.get("latitude").getAsDouble(),
                    location.get("longitude").getAsDouble());
            if (km < bestKm) {
                bestKm = km;
                bestArea = name;
            }
        }

        if (bestArea == null) {
            throw new IllegalStateException("No forecast areas found in the API response");
        }

        JsonObject result = new JsonObject();
        result.addProperty("area", bestArea);
        result.addProperty("forecast", forecastByArea.get(bestArea));
        result.addProperty("distanceKm", Math.round(bestKm * 10.0) / 10.0);
        result.addProperty("farAway", bestKm > MAX_DISTANCE_KM);
        result.addProperty("validPeriod", item.has("valid_period")
                ? item.getAsJsonObject("valid_period").get("text").getAsString()
                : "");
        return result;
    }

    /** Great-circle distance in kilometres (haversine formula). */
    static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * earthRadiusKm * Math.asin(Math.sqrt(a));
    }

    private static void error(HttpServletResponse response, PrintWriter out, int status, String message) {
        response.setStatus(status);
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        out.print(body);
    }
}
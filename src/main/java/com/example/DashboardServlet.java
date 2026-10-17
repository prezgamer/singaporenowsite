package com.example;

import com.google.gson.JsonArray;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * GET /dashboard?range=today|7|14&lat=1.3521&lng=103.8198
 *
 * One page with the PSI, the 2-hour weather forecast nearest to a location, and hawker centre
 * closures. Each section loads on its own, so one failing API does not break the page.
 * Uses WeatherServlet.nearestForecast and HawkerServlet.findClosures.
 *
 * API answers are saved in memory for a few minutes so the page does not hit data.gov.sg on
 * every click. An optional API key can be given in the DATA_GOV_SG_API_KEY environment variable.
 */
@WebServlet("/dashboard")
public class DashboardServlet extends HttpServlet {

    private static final String PSI_URL = "https://api-open.data.gov.sg/v2/real-time/api/psi";
    private static final String PM25_URL = "https://api-open.data.gov.sg/v2/real-time/api/pm25";
    private static final String WEATHER_URL = "https://api-open.data.gov.sg/v2/real-time/api/two-hr-forecast";
    private static final String HAWKER_URL = "https://data.gov.sg/api/action/datastore_search"
            + "?resource_id=d_bda4baa634dd1cc7a6c7cad5f19e2d68&limit=500";

    // How long a saved answer is reused before asking data.gov.sg again
    private static final Duration PSI_MAX_AGE = Duration.ofMinutes(10);
    private static final Duration PM25_MAX_AGE = Duration.ofMinutes(10);
    private static final Duration WEATHER_MAX_AGE = Duration.ofMinutes(5);
    private static final Duration HAWKER_MAX_AGE = Duration.ofHours(1);

    private static final String PSI_READING = "psi_twenty_four_hourly";
    private static final String[] PSI_REGIONS = {"north", "east", "south", "west"};

    // Centre of Singapore (approximate), used when no location is given
    private static final double DEFAULT_LAT = 1.3521;
    private static final double DEFAULT_LNG = 103.8198;

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final DateTimeFormatter DATE_OUT = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_OUT = DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a", Locale.ENGLISH);

    static final String HEAD = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Singapore dashboard</title>
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
            body { font-family: system-ui, sans-serif; max-width: 900px; margin: 0 auto; padding: 16px; line-height: 1.5; }
            section { margin: 24px 0; padding-bottom: 8px; border-bottom: 1px solid #ddd; }
            table { border-collapse: collapse; width: 100%; }
            th, td { border: 1px solid #ccc; padding: 6px 10px; text-align: left; }
            .big { font-size: 1.4em; margin: 0; }
            .wx { display: flex; align-items: center; gap: 16px; }
            .wx p { margin: 2px 0; }
            .wx-icon { font-size: 3.5rem; line-height: 1; }
            .muted { color: #666; }
            .error { color: #b00020; }
            #psi-map { height: 360px; border: 1px solid #ccc; margin: 8px 0; }
            .psi-badge { width: 100%; height: 100%; box-sizing: border-box; padding-top: 3px; border: 2px solid #fff; border-radius: 10px; box-shadow: 0 1px 4px rgba(0, 0, 0, 0.4); text-align: center; line-height: 1.15; }
            .psi-badge b { font-size: 1.15rem; }
            .switch { display: flex; gap: 8px; margin: 8px 0; }
            .switch button { padding: 6px 14px; border: 1px solid #1565c0; background: #fff; color: #1565c0; border-radius: 6px; cursor: pointer; font: inherit; }
            .switch button[aria-pressed="true"] { background: #1565c0; color: #fff; }
            .legend { margin: 4px 0 12px; }
            .legend span { display: inline-block; padding: 2px 8px; margin: 2px 4px 2px 0; border-radius: 4px; font-size: 0.85rem; }
            details { margin: 8px 0; }
            </style>
            </head>
            <body>
            """;

    private static final String LOCATE_SCRIPT = """
            <script>
            document.getElementById("locate").addEventListener("click", function () {
                var msg = document.getElementById("locmsg");
                if (!navigator.geolocation) { msg.textContent = "Your browser does not support location."; return; }
                msg.textContent = "Waiting for permission...";
                navigator.geolocation.getCurrentPosition(function (pos) {
                    var params = new URLSearchParams(window.location.search);
                    params.set("lat", pos.coords.latitude);
                    params.set("lng", pos.coords.longitude);
                    window.location.search = params.toString();
                }, function (err) {
                    msg.textContent = "Could not get your location (" + err.message + ").";
                }, { timeout: 10000 });
            });
            </script>
            """;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String range = request.getParameter("range");
        int days = "14".equals(range) ? 14 : "7".equals(range) ? 7 : 0;

        double[] location = parseLocation(request.getParameter("lat"), request.getParameter("lng"));
        double lat = location[0];
        double lng = location[1];

        LocalDate today = LocalDate.now(SINGAPORE);
        LocalDate to = today.plusDays(days);

        // Start all three requests at once, so the page waits for the slowest one, not the sum.
        // Answers are cached for a while, so clicking buttons does not hit data.gov.sg every time.
        CompletableFuture<String> psi = get("PSI", PSI_URL, PSI_MAX_AGE);
        CompletableFuture<String> pm25 = get("PM2.5", PM25_URL, PM25_MAX_AGE);
        CompletableFuture<String> weather = get("Weather", WEATHER_URL, WEATHER_MAX_AGE);
        CompletableFuture<String> hawker = get("Hawker closure", HAWKER_URL, HAWKER_MAX_AGE);

        // PM2.5 is an extra: if it fails, the PSI part still shows.
        String psiHtml = section("Air quality", "",
                () -> renderPsi(body(psi, "PSI"), optionalBody(pm25, "PM2.5")));
        String weatherHtml = section("Weather", weatherControls(lat, lng),
                () -> renderWeather(body(weather, "Weather"), lat, lng));
        String hawkerHtml = section("Hawker centre closures", hawkerControls(lat, lng, days),
                () -> renderHawker(body(hawker, "Hawker closure"), today, to));

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        out.print(HEAD);
        out.println("<h1>Singapore today</h1>");
        out.println("<p class=\"muted\">" + DATE_OUT.format(today) + "</p>");
        out.println(psiHtml);
        out.println(weatherHtml);
        out.println(hawkerHtml);
        out.println("<p><a href=\"./\">Back</a></p>");
        out.println("</body></html>");
    }

    // ---------- Calling the APIs (with a small cache) ----------

    /** A saved API answer and when we got it. */
    private record Cached(String body, long savedAtMillis) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Returns the API answer for a URL. A saved answer younger than maxAge is used without calling
     * the API at all. If the API fails (for example HTTP 429 "too many requests"), an older saved
     * answer is used instead of showing an error; an error only appears if nothing is saved yet.
     */
    CompletableFuture<String> get(String name, String url, Duration maxAge) {
        Cached saved = cache.get(url);
        if (saved != null && System.currentTimeMillis() - saved.savedAtMillis() < maxAge.toMillis()) {
            return CompletableFuture.completedFuture(saved.body());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET();
        String apiKey = System.getenv("DATA_GOV_SG_API_KEY"); // optional: higher rate limits
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("x-api-key", apiKey.trim());
        }
        HttpRequest request = builder.build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error == null && response.statusCode() == 200) {
                        cache.put(url, new Cached(response.body(), System.currentTimeMillis()));
                        return response.body();
                    }
                    if (saved != null) {
                        return saved.body();
                    }
                    if (error != null) {
                        throw new IllegalStateException(name + " API could not be reached");
                    }
                    throw new IllegalStateException(name + " API returned HTTP " + response.statusCode()
                            + (response.statusCode() == 429
                                    ? " (too many requests; wait a minute, then refresh)" : ""));
                });
    }

    static String body(CompletableFuture<String> future, String name) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " request was interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalStateException problem) {
                throw problem;
            }
            throw new IllegalStateException(name + " API could not be reached");
        } catch (TimeoutException e) {
            throw new IllegalStateException(name + " API could not be reached");
        }
    }

    /** Like body(), but returns null instead of throwing, for data that is nice to have. */
    static String optionalBody(CompletableFuture<String> future, String name) {
        try {
            return body(future, name);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** Wraps one section; if building its content fails, shows an error instead of breaking the page. */
    static String section(String title, String headerHtml, Supplier<String> content) {
        String html;
        try {
            html = content.get();
        } catch (RuntimeException e) {
            html = "<p class=\"error\">Unavailable: " + escape(String.valueOf(e.getMessage())) + "</p>";
        }
        return "<section><h2>" + title + "</h2>" + headerHtml + html + "</section>";
    }

    // ---------- PSI ----------

    private record Band(String label, int max, String background, String text) {}

    // PSI bands: label, highest PSI in the band, badge background, badge text colour
    private static final Band[] PSI_BANDS = {
        new Band("Good", 50, "#2e7d32", "#ffffff"),
        new Band("Moderate", 100, "#1565c0", "#ffffff"),
        new Band("Unhealthy", 200, "#f9a825", "#000000"),
        new Band("Very unhealthy", 300, "#ef6c00", "#000000"),
        new Band("Hazardous", Integer.MAX_VALUE, "#b71c1c", "#ffffff")
    };

    // 1-hour PM2.5 bands (NEA, in micrograms per cubic metre)
    private static final Band[] PM25_BANDS = {
        new Band("Normal", 55, "#2e7d32", "#ffffff"),
        new Band("Elevated", 150, "#f9a825", "#000000"),
        new Band("High", 250, "#ef6c00", "#000000"),
        new Band("Very high", Integer.MAX_VALUE, "#b71c1c", "#ffffff")
    };

    // Draws the PSI_POINTS (written just before this script) as coloured badges on a Leaflet map.
    // The two buttons switch the badges between PSI (24-hour) and PM2.5 (1-hour) without reloading.
    private static final String PSI_MAP_SCRIPT = """
            <script>
            (function () {
                var el = document.getElementById("psi-map");
                var bar = document.getElementById("mode-bar");
                if (typeof L === "undefined") {
                    el.textContent = "The map could not be loaded (check your internet connection).";
                    if (bar) { bar.hidden = true; }
                    return;
                }
                var map = L.map(el, { scrollWheelZoom: false });
                L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
                    maxZoom: 18,
                    attribution: "&copy; OpenStreetMap contributors"
                }).addTo(map);

                var markers = L.layerGroup().addTo(map);
                var bounds = PSI_POINTS.map(function (p) { return [p.lat, p.lng]; });
                function has(v) { return v !== undefined && v !== null; }

                function draw(mode) {
                    markers.clearLayers();
                    PSI_POINTS.forEach(function (p) {
                        var usePm = mode === "pm25" && has(p.pm25);
                        var bg = usePm ? p.pm25Bg : p.bg;
                        var fg = usePm ? p.pm25Fg : p.fg;
                        var value = usePm ? p.pm25 : p.value;
                        var unit = usePm ? "\u00b5g/m\u00b3" : "PSI";
                        var label = p.name + ": PSI " + p.value + " (" + p.level + ")"
                            + (has(p.pm25) ? ", PM2.5 " + p.pm25 + " \u00b5g/m\u00b3 (" + p.pm25Level + ")" : "");
                        var icon = L.divIcon({
                            className: "",
                            html: '<div class="psi-badge" style="background:' + bg + ';color:' + fg + '">'
                                + '<small>' + p.name + '</small><br><b>' + value + '</b><br><small>' + unit + '</small></div>',
                            iconSize: [72, 60],
                            iconAnchor: [36, 30]
                        });
                        L.marker([p.lat, p.lng], { icon: icon, title: label }).bindPopup(label).addTo(markers);
                    });
                }

                var buttonPsi = document.getElementById("mode-psi");
                var buttonPm = document.getElementById("mode-pm25");
                function setMode(mode) {
                    draw(mode);
                    if (buttonPsi) { buttonPsi.setAttribute("aria-pressed", mode === "psi"); }
                    if (buttonPm) { buttonPm.setAttribute("aria-pressed", mode === "pm25"); }
                    var legendPsi = document.getElementById("legend-psi");
                    var legendPm = document.getElementById("legend-pm25");
                    if (legendPsi) { legendPsi.hidden = mode !== "psi"; }
                    if (legendPm) { legendPm.hidden = mode !== "pm25"; }
                }
                if (buttonPsi) { buttonPsi.addEventListener("click", function () { setMode("psi"); }); }
                if (buttonPm) { buttonPm.addEventListener("click", function () { setMode("pm25"); }); }

                setMode("psi");
                map.fitBounds(bounds, { padding: [50, 50] });
            })();
            </script>
            """;

    static String renderPsi(String json) {
        return renderPsi(json, null);
    }

    /** The 1-hour PM2.5 readings (north/east/south/west) and when they were taken. */
    private record Pm25(Map<String, Long> values, String time) {}

    /** Reads the PM2.5 response; returns null if it is missing or not in the expected shape. */
    private static Pm25 parsePm25(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonObject item = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("data")
                    .getAsJsonArray("items").get(0).getAsJsonObject();
            JsonObject readings = item.getAsJsonObject("readings").getAsJsonObject("pm25_one_hourly");
            Map<String, Long> values = new HashMap<>();
            for (String region : PSI_REGIONS) {
                JsonElement v = readings.get(region);
                if (v != null && v.isJsonPrimitive()) {
                    values.put(region, Math.round(v.getAsDouble()));
                }
            }
            return values.isEmpty() ? null : new Pm25(values, item.get("timestamp").getAsString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** PSI table and map; pm25Json (may be null) adds the 1-hour PM2.5 for each region. */
    static String renderPsi(String json, String pm25Json) {
        JsonObject data = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("data");
        JsonObject item = data.getAsJsonArray("items").get(0).getAsJsonObject();
        JsonObject values = item.getAsJsonObject("readings").getAsJsonObject(PSI_READING);
        if (values == null) {
            throw new IllegalStateException("Reading '" + PSI_READING + "' not found in the API response");
        }

        Map<String, double[]> locations = regionLocations(data);
        Pm25 pm25 = parsePm25(pm25Json);
        JsonArray points = new JsonArray();

        StringBuilder table = new StringBuilder();
        table.append("<table><tr><th>Region</th><th>PSI</th><th>Level</th>")
                .append(pm25 == null ? "" : "<th>PM2.5, 1-hour (&micro;g/m&sup3;)</th><th>PM2.5 level</th>")
                .append("</tr>");
        for (String region : PSI_REGIONS) {
            int value = values.get(region).getAsInt();
            Band band = band(value);
            table.append("<tr><td>").append(capitalize(region)).append("</td><td>")
                    .append(value).append("</td><td>").append(band.label()).append("</td>");
            Long pm = pm25 == null ? null : pm25.values().get(region);
            if (pm25 != null) {
                table.append("<td>").append(pm == null ? "&ndash;" : pm.toString()).append("</td>")
                        .append("<td>").append(pm == null ? "&ndash;" : pm25Band(pm).label()).append("</td>");
            }
            table.append("</tr>");

            double[] where = locations.get(region);
            if (where != null) {
                JsonObject point = new JsonObject();
                point.addProperty("name", capitalize(region));
                point.addProperty("value", value);
                point.addProperty("level", band.label());
                point.addProperty("lat", where[0]);
                point.addProperty("lng", where[1]);
                point.addProperty("bg", band.background());
                point.addProperty("fg", band.text());
                if (pm != null) {
                    Band pmBand = pm25Band(pm);
                    point.addProperty("pm25", pm);
                    point.addProperty("pm25Level", pmBand.label());
                    point.addProperty("pm25Bg", pmBand.background());
                    point.addProperty("pm25Fg", pmBand.text());
                }
                points.add(point);
            }
        }
        table.append("</table>");

        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"muted\">Reading time: ")
                .append(escape(formatTime(item.get("timestamp").getAsString())));
        if (pm25 != null) {
            sb.append(" &middot; PM2.5: ").append(escape(formatTime(pm25.time())));
        }
        sb.append("</p>");
        if (pm25 == null) {
            sb.append("<p class=\"muted\">1-hour PM2.5 is not available right now.</p>");
        }

        if (points.isEmpty()) {
            sb.append(table); // no coordinates in the response: just show the table
        } else {
            if (pm25 != null) {
                sb.append("<div id=\"mode-bar\" class=\"switch\">"
                        + "<button type=\"button\" id=\"mode-psi\" aria-pressed=\"true\">PSI (24-hour)</button>"
                        + "<button type=\"button\" id=\"mode-pm25\" aria-pressed=\"false\">PM2.5 (1-hour)</button>"
                        + "</div>");
            }
            sb.append("<div id=\"psi-map\"></div>").append(legend("legend-psi", PSI_BANDS, false, ""));
            if (pm25 != null) {
                sb.append(legend("legend-pm25", PM25_BANDS, true, "&micro;g/m&sup3;, 1-hour"));
            }
            sb.append("<script>var PSI_POINTS = ")
                    .append(points.toString().replace("</", "<\\/"))
                    .append(";</script>");
            sb.append(PSI_MAP_SCRIPT);
            sb.append("<details><summary>Show as table</summary>").append(table).append("</details>");
        }
        return sb.toString();
    }

    /** Region name (lower case) to {latitude, longitude}, from the response's regionMetadata. */
    private static Map<String, double[]> regionLocations(JsonObject data) {
        Map<String, double[]> locations = new HashMap<>();
        try {
            JsonArray metadata = data.getAsJsonArray("regionMetadata");
            if (metadata == null) {
                return locations;
            }
            for (JsonElement element : metadata) {
                JsonObject region = element.getAsJsonObject();
                JsonObject label = region.getAsJsonObject("labelLocation");
                locations.put(region.get("name").getAsString().toLowerCase(Locale.ROOT),
                        new double[] {label.get("latitude").getAsDouble(), label.get("longitude").getAsDouble()});
            }
        } catch (RuntimeException e) {
            // unreadable metadata: keep what we have; the table still shows every region
        }
        return locations;
    }

    private static Band bandFor(Band[] bands, long value) {
        for (Band b : bands) {
            if (value <= b.max()) {
                return b;
            }
        }
        return bands[bands.length - 1];
    }

    private static Band band(int psi) {
        return bandFor(PSI_BANDS, psi);
    }

    private static Band pm25Band(long pm25) {
        return bandFor(PM25_BANDS, pm25);
    }

    static String psiBand(int psi) {
        return band(psi).label();
    }

    static String pm25BandName(long pm25) {
        return pm25Band(pm25).label();
    }

    private static String legend(String id, Band[] bands, boolean hidden, String note) {
        StringBuilder sb = new StringBuilder("<p class=\"legend\" id=\"" + id + "\"" + (hidden ? " hidden" : "") + ">");
        int from = 0;
        for (Band b : bands) {
            String range = b.max() == Integer.MAX_VALUE ? from + "+" : from + "&ndash;" + b.max();
            sb.append("<span style=\"background:").append(b.background()).append(";color:").append(b.text())
                    .append("\">").append(b.label()).append(" ").append(range).append("</span>");
            from = b.max() + 1;
        }
        if (!note.isEmpty()) {
            sb.append(" <small class=\"muted\">").append(note).append("</small>");
        }
        return sb.append("</p>").toString();
    }

    // ---------- Weather ----------

    static String weatherControls(double lat, double lng) {
        return "<p class=\"muted\">Nearest forecast area to "
                + String.format(Locale.ROOT, "%.4f, %.4f", lat, lng) + ". "
                + "<button type=\"button\" id=\"locate\">Use my location</button> "
                + "<span id=\"locmsg\"></span></p>"
                + LOCATE_SCRIPT;
    }

    static String renderWeather(String json, double lat, double lng) {
        JsonObject w = WeatherServlet.nearestForecast(json, lat, lng);
        double km = w.get("distanceKm").getAsDouble();
        if (w.get("farAway").getAsBoolean()) {
            return "<p>This location seems to be outside Singapore (the nearest forecast area is "
                    + km + " km away).</p>";
        }
        String forecast = w.get("forecast").getAsString();
        String icon = weatherIcon(forecast);
        String iconHtml = icon.isEmpty() ? ""
                : "<div class=\"wx-icon\" role=\"img\" aria-label=\"" + escape(forecast) + "\">" + icon + "</div>";
        return "<div class=\"wx\">" + iconHtml + "<div>"
                + "<p class=\"big\">" + escape(w.get("area").getAsString()) + ": " + escape(forecast) + "</p>"
                + "<p class=\"muted\">2-hour forecast, " + escape(w.get("validPeriod").getAsString())
                + " &middot; about " + km + " km from this location</p>"
                + "</div></div>";
    }

    /**
     * Picks an icon (as HTML character references, so the source file stays plain ASCII)
     * for an NEA 2-hour forecast such as "Partly Cloudy (Night)" or "Thundery Showers".
     * Matches on keywords, so wording we have not seen still gets a sensible icon;
     * returns "" when nothing matches, and the page then shows the text only.
     */
    static String weatherIcon(String forecast) {
        String f = forecast == null ? "" : forecast.toLowerCase(Locale.ROOT);
        boolean night = f.contains("night");
        if (f.contains("thunder")) return "&#x26C8;&#xFE0F;";                       // storm cloud
        if (f.contains("shower") || f.contains("rain")) {
            boolean light = f.contains("light") || f.contains("passing");
            return light && !night ? "&#x1F326;&#xFE0F;" : "&#x1F327;&#xFE0F;";  // sun behind rain / rain cloud
        }
        if (f.contains("haz") || f.contains("mist") || f.contains("fog")) return "&#x1F32B;&#xFE0F;"; // fog
        if (f.contains("wind")) return "&#x1F4A8;";                                // wind
        if (f.contains("partly")) return night ? "&#x1F319;&#x2601;&#xFE0F;" : "&#x26C5;"; // moon+cloud / sun behind cloud
        if (f.contains("cloud")) return "&#x2601;&#xFE0F;";                        // cloud
        if (f.contains("fair") || f.contains("sunny") || f.contains("clear"))
            return night ? "&#x1F319;" : "&#x2600;&#xFE0F;";                       // moon / sun
        return "";
    }

    // ---------- Hawker closures ----------

    static String hawkerControls(double lat, double lng, int days) {
        return "<form method=\"get\">"
                + "<input type=\"hidden\" name=\"lat\" value=\"" + lat + "\">"
                + "<input type=\"hidden\" name=\"lng\" value=\"" + lng + "\">"
                + button("today", "Today", days == 0) + " "
                + button("7", "Next 7 days", days == 7) + " "
                + button("14", "Next 14 days", days == 14)
                + "</form>";
    }

    static String renderHawker(String json, LocalDate from, LocalDate to) {
        List<HawkerServlet.Closure> closures = HawkerServlet.findClosures(json, from, to);

        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"muted\">Closures from ").append(DATE_OUT.format(from))
                .append(" to ").append(DATE_OUT.format(to)).append(": ")
                .append(closures.size()).append(" found</p>");

        if (closures.isEmpty()) {
            sb.append("<p>No hawker centres are closing in this period.</p>");
            return sb.toString();
        }

        sb.append("<table><tr><th>Hawker centre</th><th>Address</th><th>Type</th>"
                + "<th>From</th><th>To</th><th>Remarks</th></tr>");
        for (HawkerServlet.Closure c : closures) {
            sb.append("<tr><td>").append(escape(c.name())).append("</td>")
                    .append("<td>").append(escape(c.address())).append("</td>")
                    .append("<td>").append(escape(c.type())).append("</td>")
                    .append("<td>").append(DATE_OUT.format(c.start())).append("</td>")
                    .append("<td>").append(DATE_OUT.format(c.end())).append("</td>")
                    .append("<td>").append(escape(c.remarks())).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    // ---------- helpers ----------

    /** Reads lat/lng; falls back to the centre of Singapore if missing, not a number, or out of range. */
    static double[] parseLocation(String latParam, String lngParam) {
        try {
            double lat = Double.parseDouble(latParam);
            double lng = Double.parseDouble(lngParam);
            if (lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180) {
                return new double[] {lat, lng};
            }
        } catch (NullPointerException | NumberFormatException e) {
            // fall through to the default
        }
        return new double[] {DEFAULT_LAT, DEFAULT_LNG};
    }

    static String formatTime(String iso) {
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(SINGAPORE).format(TIME_OUT);
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    private static String button(String value, String label, boolean active) {
        return "<button name=\"range\" value=\"" + value + "\"" + (active ? " disabled" : "") + ">"
                + label + "</button>";
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
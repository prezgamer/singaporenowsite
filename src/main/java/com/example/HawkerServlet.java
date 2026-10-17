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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * GET /hawker?range=today|7|14 - lists hawker centres closed during that window.
 * Data: NEA "Dates of Hawker Centres Closure" on data.gov.sg.
 */
@WebServlet("/hawker")
public class HawkerServlet extends HttpServlet {

    private static final String API_URL = "https://data.gov.sg/api/action/datastore_search"
            + "?resource_id=d_bda4baa634dd1cc7a6c7cad5f19e2d68&limit=500";

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final DateTimeFormatter DATE_IN = DateTimeFormatter.ofPattern("d/M/uuuu");
    private static final DateTimeFormatter DATE_OUT = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    // label, start-date field, end-date field, remarks field
    private static final String[][] PERIODS = {
        {"Q1 cleaning", "q1_cleaningstartdate", "q1_cleaningenddate", "remarks_q1"},
        {"Q2 cleaning", "q2_cleaningstartdate", "q2_cleaningenddate", "remarks_q2"},
        {"Q3 cleaning", "q3_cleaningstartdate", "q3_cleaningenddate", "remarks_q3"},
        {"Q4 cleaning", "q4_cleaningstartdate", "q4_cleaningenddate", "remarks_q4"},
        {"Other works", "other_works_startdate", "other_works_enddate", "remarks_other_works"}
    };

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    record Closure(String name, String address, String type,
                   LocalDate start, LocalDate end, String remarks) {}

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String range = request.getParameter("range");
        int days = "14".equals(range) ? 14 : "7".equals(range) ? 7 : 0;

        LocalDate from = LocalDate.now(SINGAPORE);
        LocalDate to = from.plusDays(days);

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        try {
            HttpRequest apiRequest = HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> apiResponse =
                    client.send(apiRequest, HttpResponse.BodyHandlers.ofString());

            if (apiResponse.statusCode() != 200) {
                fail(response, out, "Hawker API returned HTTP " + apiResponse.statusCode());
                return;
            }

            List<Closure> closures = findClosures(apiResponse.body(), from, to);
            render(out, days, from, to, closures);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(response, out, "Request was interrupted");
        } catch (IOException | RuntimeException e) {
            fail(response, out, "Could not get hawker closure data: " + e.getMessage());
        }
    }

    /** Returns every closure period that overlaps the window [from, to], earliest first. */
    static List<Closure> findClosures(String json, LocalDate from, LocalDate to) {
        JsonArray records = JsonParser.parseString(json)
                .getAsJsonObject()
                .getAsJsonObject("result")
                .getAsJsonArray("records");

        List<Closure> found = new ArrayList<>();
        int datesRead = 0;

        for (JsonElement element : records) {
            JsonObject record = element.getAsJsonObject();
            String name = text(record, "name");
            String address = text(record, "address_myenv");

            for (String[] period : PERIODS) {
                LocalDate start = date(text(record, period[1]));
                if (start == null) {
                    continue; // empty, "NA" or "TBC"
                }
                datesRead++;

                LocalDate end = date(text(record, period[2]));
                if (end == null || end.isBefore(start)) {
                    end = start; // no usable end date: treat as a one-day closure
                }

                if (!end.isBefore(from) && !start.isAfter(to)) {
                    String remarks = text(record, period[3]);
                    if (remarks.equalsIgnoreCase("NA")) {
                        remarks = "";
                    }
                    found.add(new Closure(name, address, period[0], start, end, remarks));
                }
            }
        }

        if (datesRead == 0) {
            throw new IllegalStateException(
                    "No closure dates could be read (the dataset's field names or date format may have changed)");
        }

        found.sort(Comparator.comparing(Closure::start).thenComparing(Closure::name));
        return found;
    }

    private static void render(PrintWriter out, int days, LocalDate from, LocalDate to,
                               List<Closure> closures) {
        out.println("<html><head><meta charset=\"UTF-8\"><title>Hawker centre closures</title></head><body>");
        out.println("<h1>Hawker centre closures</h1>");

        out.println("<form method=\"get\">");
        button(out, "today", "Today", days == 0);
        button(out, "7", "Next 7 days", days == 7);
        button(out, "14", "Next 14 days", days == 14);
        out.println("</form>");

        out.println("<p>Closures from " + DATE_OUT.format(from) + " to " + DATE_OUT.format(to)
                + ": " + closures.size() + " found</p>");

        if (closures.isEmpty()) {
            out.println("<p>No hawker centres are closing in this period.</p>");
        } else {
            out.println("<table border=\"1\" cellpadding=\"6\">");
            out.println("<tr><th>Hawker centre</th><th>Address</th><th>Type</th>"
                    + "<th>From</th><th>To</th><th>Remarks</th></tr>");
            for (Closure c : closures) {
                out.println("<tr><td>" + escape(c.name()) + "</td>"
                        + "<td>" + escape(c.address()) + "</td>"
                        + "<td>" + escape(c.type()) + "</td>"
                        + "<td>" + DATE_OUT.format(c.start()) + "</td>"
                        + "<td>" + DATE_OUT.format(c.end()) + "</td>"
                        + "<td>" + escape(c.remarks()) + "</td></tr>");
            }
            out.println("</table>");
        }

        out.println("<p><a href=\"./\">Back</a></p>");
        out.println("</body></html>");
    }

    private static void button(PrintWriter out, String value, String label, boolean active) {
        out.println("<button name=\"range\" value=\"" + value + "\""
                + (active ? " disabled" : "") + ">" + label + "</button>");
    }

    private static void fail(HttpServletResponse response, PrintWriter out, String message) {
        response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        out.println("<html><body><h1>Hawker closures unavailable</h1><p>"
                + escape(message) + "</p></body></html>");
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return (element == null || element.isJsonNull()) ? "" : element.getAsString().trim();
    }

    private static LocalDate date(String s) {
        try {
            return LocalDate.parse(s, DATE_IN);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
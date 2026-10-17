package com.example;

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

@WebServlet("/psi")
public class PsiServlet extends HttpServlet {

    private static final String API_URL = "https://api-open.data.gov.sg/v2/real-time/api/psi";

    private static final String READING = "psi_twenty_four_hourly";

    private static final String[] REGIONS = {"north", "east", "south", "west"};

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        try {
            HttpRequest apiRequest = HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> apiResponse =
                    client.send(apiRequest, HttpResponse.BodyHandlers.ofString());

            if (apiResponse.statusCode() != 200) {
                fail(response, out, "PSI API returned HTTP " + apiResponse.statusCode());
                return;
            }

            JsonObject item = JsonParser.parseString(apiResponse.body())
                    .getAsJsonObject()
                    .getAsJsonObject("data")
                    .getAsJsonArray("items")
                    .get(0)
                    .getAsJsonObject();

            String timestamp = item.get("timestamp").getAsString();
            JsonObject values = item.getAsJsonObject("readings").getAsJsonObject(READING);
            if (values == null) {
                fail(response, out, "Reading '" + READING + "' not found in the API response");
                return;
            }

            out.println("<html><head><meta charset=\"UTF-8\"><title>PSI</title></head><body>");
            out.println("<h1>PSI (" + escape(READING) + ")</h1>");
            out.println("<p>Reading time: " + escape(timestamp) + "</p>");
            out.println("<table border=\"1\" cellpadding=\"6\">");
            out.println("<tr><th>Region</th><th>PSI</th></tr>");
            for (String region : REGIONS) {
                int value = values.get(region).getAsInt();
                out.println("<tr><td>" + region + "</td><td>" + value + "</td></tr>");
            }
            out.println("</table>");
            out.println("<p><a href=\"./\">Back</a></p>");
            out.println("</body></html>");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(response, out, "Request was interrupted");
        } catch (IOException | RuntimeException e) {
            // IOException: network problem. RuntimeException: unexpected JSON shape.
            fail(response, out, "Could not get PSI data: " + e.getMessage());
        }
    }

    private static void fail(HttpServletResponse response, PrintWriter out, String message) {
        response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        out.println("<html><body><h1>PSI unavailable</h1><p>" + escape(message) + "</p></body></html>");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
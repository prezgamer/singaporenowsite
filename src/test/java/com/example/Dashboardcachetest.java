package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Talks to a tiny local HTTP server, never to the real data.gov.sg. */
class DashboardCacheTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile boolean limited;
    private DashboardServlet servlet;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            hits.incrementAndGet();
            byte[] b = (limited ? "{}" : "{\"v\":1}").getBytes();
            ex.sendResponseHeaders(limited ? 429 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/always429", ex -> {
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        servlet = new DashboardServlet();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String fetch(String name, String path, Duration maxAge) {
        return DashboardServlet.body(servlet.get(name, base + path, maxAge), name);
    }

    @Test
    void secondCallWithinMaxAgeUsesTheSavedAnswer() {
        String first = fetch("T", "/ok", Duration.ofMinutes(5));
        assertEquals("{\"v\":1}", first);
        assertEquals(1, hits.get());

        String second = fetch("T", "/ok", Duration.ofMinutes(5));
        assertEquals(first, second);
        assertEquals(1, hits.get(), "no new request while the saved answer is fresh");
    }

    @Test
    void rateLimitedAfterExpiryShowsTheSavedAnswer() {
        String first = fetch("T", "/ok", Duration.ofMinutes(5));
        limited = true;

        String again = fetch("T", "/ok", Duration.ZERO);
        assertEquals(first, again);
        assertEquals(2, hits.get());
    }

    @Test
    void savedAnswerSurvivesRepeatedRateLimits() {
        String first = fetch("T", "/ok", Duration.ofMinutes(5));
        limited = true;
        fetch("T", "/ok", Duration.ZERO);
        assertEquals(first, fetch("T", "/ok", Duration.ZERO));
    }

    @Test
    void rateLimitedWithNothingSavedGivesAClearMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> fetch("PSI", "/always429", Duration.ofMinutes(5)));
        assertTrue(e.getMessage().contains("PSI API returned HTTP 429"), e.getMessage());
        assertTrue(e.getMessage().contains("too many requests"), e.getMessage());
    }

    @Test
    void unreachableWithNothingSavedSaysSo() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DashboardServlet.body(
                        servlet.get("Weather", "http://127.0.0.1:1/x", Duration.ofMinutes(5)), "Weather"));
        assertEquals("Weather API could not be reached", e.getMessage());
    }
}
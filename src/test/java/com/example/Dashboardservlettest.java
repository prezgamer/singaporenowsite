package com.example;

import static com.example.TestData.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Tests the HTML the dashboard produces from sample API responses. No network needed. */
class DashboardServletTest {

    private static final String UNHEALTHY_CHIP =
            "<span class=\"chip\" style=\"background:#f9a825;color:#000000\">Unhealthy</span>";
    private static final String ELEVATED_CHIP =
            "<span class=\"chip\" style=\"background:#f9a825;color:#000000\">Elevated</span>";

    // ---------- PSI ----------

    @Test
    void psiShowsRegionsInOrder() {
        String psi = DashboardServlet.renderPsi(TestData.PSI_JSON);
        assertTrue(psi.indexOf("North") < psi.indexOf("East"));
        assertTrue(psi.indexOf("East") < psi.indexOf("South"));
        assertTrue(psi.indexOf("South") < psi.indexOf("West"));
    }

    @Test
    void psiShowsValuesLevelAndTime() {
        String psi = DashboardServlet.renderPsi(TestData.PSI_JSON);
        assertTrue(psi.contains(">159<"));
        assertTrue(psi.contains("Unhealthy"));
        assertTrue(psi.contains("16 Oct 2026, 8:00"));
    }

    @Test
    void psiLeavesOutCentral() {
        assertFalse(DashboardServlet.renderPsi(TestData.PSI_JSON).contains("Central"));
    }

    @Test
    void missingPsiReadingGivesAClearError() {
        assertThrows(IllegalStateException.class, () -> DashboardServlet.renderPsi(
                "{\"data\":{\"items\":[{\"timestamp\":\"x\",\"readings\":{}}]}}"));
    }

    @Test
    void psiBandBoundaries() {
        assertEquals("Good", DashboardServlet.psiBand(0));
        assertEquals("Good", DashboardServlet.psiBand(50));
        assertEquals("Moderate", DashboardServlet.psiBand(51));
        assertEquals("Moderate", DashboardServlet.psiBand(100));
        assertEquals("Unhealthy", DashboardServlet.psiBand(101));
        assertEquals("Unhealthy", DashboardServlet.psiBand(200));
        assertEquals("Very unhealthy", DashboardServlet.psiBand(222));
        assertEquals("Hazardous", DashboardServlet.psiBand(301));
    }

    @Test
    void pm25BandBoundaries() {
        assertEquals("Normal", DashboardServlet.pm25BandName(55));
        assertEquals("Elevated", DashboardServlet.pm25BandName(56));
        assertEquals("Elevated", DashboardServlet.pm25BandName(150));
        assertEquals("High", DashboardServlet.pm25BandName(151));
        assertEquals("High", DashboardServlet.pm25BandName(250));
        assertEquals("Very high", DashboardServlet.pm25BandName(251));
    }

    // ---------- PSI map ----------

    @Test
    void mapHasContainerDataAndLegend() {
        String map = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON);
        assertTrue(map.contains("id=\"psi-map\""));
        assertTrue(map.contains("var PSI_POINTS"));
        assertTrue(map.contains("Good 0&ndash;50"));
        assertTrue(map.contains("Moderate 51&ndash;100"));
        assertTrue(map.contains("Unhealthy 101&ndash;200"));
        assertTrue(map.contains("Very unhealthy 201&ndash;300"));
        assertTrue(map.contains("Hazardous 301+"));
    }

    @Test
    void mapPlotsFourRegionsAndLeavesOutCentral() {
        String map = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON);
        assertEquals(4, count(map, "\"lat\":"));
        assertFalse(map.contains("Central"));
        assertTrue(map.contains("\"lat\":1.41803"), "coordinates come from the API metadata");
    }

    @Test
    void mapBadgesUseTheBandColours() {
        String map = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON);
        assertTrue(map.contains("\"bg\":\"#f9a825\""));
        assertTrue(map.contains("\"fg\":\"#000000\""));
    }

    @Test
    void mapComesBeforeTheTableAndTableIsInsideDetails() {
        String map = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON);
        assertTrue(map.indexOf("psi-map") < map.indexOf("<details>"));
        assertTrue(map.contains("<td>159</td>"));
    }

    @Test
    void noRegionMetadataMeansPlainTableAndNoMap() {
        String psi = DashboardServlet.renderPsi(TestData.PSI_JSON);
        assertFalse(psi.contains("psi-map"));
        assertTrue(psi.contains("<table class=\"stack psi-table\">"));
    }

    @Test
    void regionMissingFromMetadataIsSkippedOnTheMapButStaysInTheTable() {
        String partial = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON.replace(
                "{\"name\":\"west\",\"labelLocation\":{\"latitude\":1.35735,\"longitude\":103.7}},", ""));
        assertEquals(3, count(partial, "\"lat\":"));
        assertTrue(partial.contains("<td>West</td>"));
    }

    @Test
    void unreadableMetadataFallsBackToTheTable() {
        String bad = DashboardServlet.renderPsi(
                TestData.PSI_JSON.replace("\"regionMetadata\":[]", "\"regionMetadata\":\"oops\""));
        assertFalse(bad.contains("psi-map"));
        assertTrue(bad.contains("<td>159</td>"));
    }

    @Test
    void mapPageHasTwoScriptBlocks() {
        assertEquals(2, count(DashboardServlet.renderPsi(TestData.PSI_MAP_JSON), "<script>"));
    }

    // ---------- PM2.5 ----------

    @Test
    void pm25ColumnsAndValuesPerRegion() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, TestData.PM25_JSON);
        assertTrue(html.contains("PM2.5, 1-hour"));
        assertTrue(html.contains("Unhealthy</span></td><td>66</td>"));
        assertTrue(html.contains("<td>113</td>"));
        assertTrue(html.contains("<td>117</td>"));
        assertTrue(html.contains("<td>125</td>"));
        assertFalse(html.contains("136"), "central is left out");
    }

    @Test
    void pm25ShownOnTheFourMapBadgesWithItsOwnTime() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, TestData.PM25_JSON);
        assertEquals(4, count(html, "\"pm25\":"));
        assertTrue(html.contains("\"pm25\":66"));
        assertTrue(html.contains("PM2.5: 16 Oct 2026, 9:00 PM"));
        assertFalse(html.contains("not available"));
    }

    @Test
    void pm25LevelAndColourPerPoint() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, TestData.PM25_JSON);
        assertTrue(html.contains("\"pm25Level\":\"Elevated\""));
        assertTrue(html.contains("\"pm25Bg\":\"#f9a825\""));
        assertTrue(html.contains("\"pm25Fg\":\"#000000\""));
    }

    @Test
    void togglePressesPsiFirstAndSitsAboveTheMap() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, TestData.PM25_JSON);
        assertTrue(html.contains("id=\"mode-psi\" aria-pressed=\"true\">PSI (24-hour)"));
        assertTrue(html.contains("id=\"mode-pm25\" aria-pressed=\"false\">PM2.5 (1-hour)"));
        assertTrue(html.indexOf("id=\"mode-bar\"") < html.indexOf("id=\"psi-map\""));
    }

    @Test
    void pm25LegendExistsButIsHiddenUntilToggled() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, TestData.PM25_JSON);
        assertTrue(html.contains("id=\"legend-pm25\" hidden"));
        assertFalse(html.contains("id=\"legend-psi\" hidden"));
        assertTrue(html.contains("Normal 0&ndash;55"));
        assertTrue(html.contains("Elevated 56&ndash;150"));
        assertTrue(html.contains("High 151&ndash;250"));
        assertTrue(html.contains("Very high 251+"));
    }

    @Test
    void tableLevelsAreColouredChips() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, TestData.PM25_JSON);
        assertTrue(html.contains("<th>PM2.5 level</th>"));
        assertTrue(html.contains("<td>" + UNHEALTHY_CHIP + "</td><td>66</td>"));
        assertTrue(html.contains("<td>66</td><td>" + ELEVATED_CHIP + "</td>"));
    }

    @Test
    void missingPm25KeepsPsiAndAddsANote() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, null);
        assertTrue(html.contains("1-hour PM2.5 is not available right now"));
        assertTrue(html.contains("<td>159</td>"));
        assertFalse(html.contains("PM2.5, 1-hour"));
        assertFalse(html.contains("\"pm25\":"));
        assertFalse(html.contains("id=\"mode-bar\""), "no toggle without PM2.5");
        assertFalse(html.contains("id=\"legend-pm25\""));
        assertTrue(html.contains("id=\"legend-psi\""));
    }

    @Test
    void pm25InTheWrongShapeIsTreatedAsMissing() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, "{\"data\":{\"items\":[{\"readings\":{}}]}}");
        assertTrue(html.contains("not available right now"));
    }

    @Test
    void pm25ThatIsNotJsonIsTreatedAsMissing() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, "not json");
        assertTrue(html.contains("not available right now"));
        assertTrue(html.contains("<td>200</td>"));
    }

    @Test
    void pm25RegionMissingGivesDashesInThatRowOnly() {
        String html = DashboardServlet.renderPsi(TestData.PSI_MAP_JSON,
                TestData.PM25_JSON.replace("\"west\":125,", ""));
        assertTrue(html.contains("<td>West</td><td>200</td>"));
        assertTrue(html.contains("Unhealthy</span></td><td>&ndash;</td><td>&ndash;</td>"));
        assertEquals(3, count(html, "\"pm25\":"));
    }

    @Test
    void optionalBodyIsNullWhenTheCallFailed() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("PM2.5 API returned HTTP 429"));
        assertNull(DashboardServlet.optionalBody(failed, "PM2.5"));
    }

    @Test
    void optionalBodyReturnsTheBodyWhenFine() {
        assertEquals("x", DashboardServlet.optionalBody(CompletableFuture.completedFuture("x"), "PM2.5"));
    }

    // ---------- Weather ----------

    @Test
    void weatherShowsNearestAreaAndForecast() {
        String weather = DashboardServlet.renderWeather(TestData.WEATHER_JSON, 1.3167, 103.8980);
        assertTrue(weather.contains("Geylang: Light Rain"));
        assertTrue(weather.contains("10.30 am to 12.30 pm"));
    }

    @Test
    void weatherFarAwayGivesAnOutsideSingaporeMessage() {
        assertTrue(DashboardServlet.renderWeather(TestData.WEATHER_JSON, 3.139, 101.687)
                .contains("outside Singapore"));
    }

    @Test
    void weatherIconsMatchTheForecastText() {
        String[][] icons = {
            {"Fair (Day)", "&#x2600;"}, {"Fair (Night)", "&#x1F319;"}, {"Fair & Warm", "&#x2600;"},
            {"Partly Cloudy (Day)", "&#x26C5;"}, {"Partly Cloudy (Night)", "&#x1F319;&#x2601;"},
            {"Cloudy", "&#x2601;"}, {"Hazy", "&#x1F32B;"}, {"Slightly Hazy", "&#x1F32B;"},
            {"Mist", "&#x1F32B;"}, {"Fog", "&#x1F32B;"}, {"Windy", "&#x1F4A8;"},
            {"Light Rain", "&#x1F326;"}, {"Moderate Rain", "&#x1F327;"}, {"Heavy Rain", "&#x1F327;"},
            {"Passing Showers", "&#x1F326;"}, {"Light Showers", "&#x1F326;"}, {"Showers", "&#x1F327;"},
            {"Heavy Showers", "&#x1F327;"}, {"Thundery Showers", "&#x26C8;"},
            {"Heavy Thundery Showers", "&#x26C8;"}, {"Heavy Thundery Showers with Gusty Winds", "&#x26C8;"},
            {"Passing Showers (Night)", "&#x1F327;"}, {"PARTLY CLOUDY (DAY)", "&#x26C5;"}
        };
        for (String[] t : icons) {
            assertTrue(DashboardServlet.weatherIcon(t[0]).startsWith(t[1]), "icon for " + t[0]);
        }
    }

    @Test
    void unknownOrNullForecastHasNoIcon() {
        assertTrue(DashboardServlet.weatherIcon("Volcanic Ash").isEmpty());
        assertTrue(DashboardServlet.weatherIcon(null).isEmpty());
    }

    @Test
    void weatherIconHasALabelForScreenReaders() {
        String weather = DashboardServlet.renderWeather(TestData.WEATHER_JSON, 1.3167, 103.8980);
        assertTrue(weather.contains("class=\"wx-icon\""));
        assertTrue(weather.contains("aria-label=\"Light Rain\""));
        assertTrue(weather.contains("&#x1F326;"));
    }

    @Test
    void unknownForecastTextIsEscapedAndHasNoIconBox() {
        String html = DashboardServlet.renderWeather(
                TestData.WEATHER_JSON.replace("Light Rain", "Volcanic <Ash>"), 1.3167, 103.8980);
        assertTrue(html.contains("Volcanic &lt;Ash&gt;"));
        assertFalse(html.contains("wx-icon\""));
    }

    // ---------- Hawker centre closures ----------

    @Test
    void hawkerListsTheLongWorksClosure() {
        LocalDate d = LocalDate.of(2026, 10, 17);
        String html = DashboardServlet.renderHawker(TestData.HAWKER_JSON, d, d);
        assertTrue(html.contains("Long Works Centre"));
        assertTrue(html.contains("1 found"));
        assertTrue(html.contains("<table class=\"stack hawker-table\">"));
    }

    @Test
    void hawkerSevenDecemberListsTwo() {
        LocalDate d = LocalDate.of(2026, 12, 7);
        String html = DashboardServlet.renderHawker(TestData.HAWKER_JSON, d, d);
        assertTrue(html.contains("2 found"));
        assertTrue(html.contains("Adam Road Food Centre"));
    }

    @Test
    void hawkerEscapesHtmlInRemarks() {
        LocalDate d = LocalDate.of(2024, 5, 20);
        assertTrue(DashboardServlet.renderHawker(TestData.HAWKER_JSON, d, d).contains("Repairs &amp; Redecoration"));
    }

    @Test
    void hawkerEmptyPeriodMessage() {
        LocalDate d = LocalDate.of(2026, 5, 1);
        assertTrue(DashboardServlet.renderHawker(TestData.HAWKER_JSON, d, d)
                .contains("No hawker centres are closing"));
    }

    // ---------- Sections stay independent ----------

    @Test
    void failedSectionShowsTheEscapedError() {
        String html = DashboardServlet.section("PSI", "", () -> { throw new IllegalStateException("boom <b>"); });
        assertTrue(html.contains("Unavailable: boom &lt;b&gt;"));
        assertTrue(html.contains("<h2>PSI</h2>"));
    }

    @Test
    void exceptionWithoutAMessageDoesNotCrashSection() {
        String html = DashboardServlet.section("X", "", () -> { throw new NullPointerException(); });
        assertTrue(html.contains("Unavailable"));
    }

    // ---------- Location and controls ----------

    @Test
    void noLocationMeansCentreOfSingapore() {
        double[] d = DashboardServlet.parseLocation(null, null);
        assertEquals(1.3521, d[0]);
        assertEquals(103.8198, d[1]);
    }

    @Test
    void badLocationValuesFallBackToTheCentre() {
        assertEquals(1.3521, DashboardServlet.parseLocation("abc", "103.9")[0]);
        assertEquals(1.3521, DashboardServlet.parseLocation("95", "103.9")[0]);
        assertEquals(1.3521, DashboardServlet.parseLocation("NaN", "103.9")[0]);
    }

    @Test
    void validLocationIsUsed() {
        double[] d = DashboardServlet.parseLocation("1.5", "103.9");
        assertEquals(1.5, d[0]);
        assertEquals(103.9, d[1]);
    }

    @Test
    void hawkerControlsDisableTheCurrentRangeAndKeepTheLocation() {
        String h = DashboardServlet.hawkerControls(1.3167, 103.898, 7);
        assertTrue(h.contains("value=\"7\" disabled"));
        assertFalse(h.contains("value=\"today\" disabled"));
        assertFalse(h.contains("value=\"14\" disabled"));
        assertTrue(h.contains("name=\"lat\" value=\"1.3167\""));
    }

    @Test
    void weatherControlsHaveTheLocateButtonOnly() {
        String w = DashboardServlet.weatherControls(1.3521, 103.8198);
        assertFalse(w.contains("Geylang"));
        assertFalse(w.contains("default"));
        assertTrue(w.contains("Use my location"));
    }

    // ---------- Whole page ----------

    @Test
    void pageSectionsAreInTheExpectedOrder() {
        String page = DashboardServlet.HEAD
                + DashboardServlet.section("Air quality", "",
                        () -> DashboardServlet.renderPsi(TestData.PSI_MAP_JSON, TestData.PM25_JSON))
                + DashboardServlet.section("Weather", "",
                        () -> DashboardServlet.renderWeather(TestData.WEATHER_JSON, 1.3521, 103.8198))
                + DashboardServlet.section("Hawker centre closures", "", () -> "x");
        assertTrue(page.indexOf("PSI (24-hour)") < page.indexOf("<h2>Weather</h2>"));
        assertTrue(page.indexOf("<h2>Weather</h2>") < page.indexOf("Hawker centre closures"));
    }

    @Test
    void pageIsMobileFriendly() {
        String head = DashboardServlet.HEAD;
        assertTrue(head.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"));
        assertTrue(head.contains("@media (max-width: 40rem)"), "phone layout rules");
        assertTrue(head.contains("table.stack"), "tables turn into cards on phones");
    }
}
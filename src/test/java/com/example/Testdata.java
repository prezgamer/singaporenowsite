package com.example;

/** Sample API responses shared by the tests. Nothing here calls the real APIs. */
final class TestData {
    private TestData() {}

    // Shaped like the live PSI response
    static final String PSI_JSON = """
        {"code":0,"data":{"regionMetadata":[],"items":[{
          "timestamp":"2026-10-16T20:00:00+08:00",
          "readings":{"psi_twenty_four_hourly":{"north":159,"east":198,"south":183,"west":200,"central":222}}
        }]}}
        """;

    // Same, with the regionMetadata the live API returns
    static final String PSI_MAP_JSON = """
        {"code":0,"data":{"regionMetadata":[
          {"name":"north","labelLocation":{"latitude":1.41803,"longitude":103.82}},
          {"name":"south","labelLocation":{"latitude":1.29587,"longitude":103.82}},
          {"name":"east","labelLocation":{"latitude":1.35735,"longitude":103.94}},
          {"name":"west","labelLocation":{"latitude":1.35735,"longitude":103.7}},
          {"name":"central","labelLocation":{"latitude":1.35735,"longitude":103.82}}],
         "items":[{
          "timestamp":"2026-10-16T20:00:00+08:00",
          "readings":{"psi_twenty_four_hourly":{"north":159,"east":198,"south":183,"west":200,"central":222}}
        }]}}
        """;

    // Shaped like the live /pm25 response
    static final String PM25_JSON = """
        {"code":0,"data":{"regionMetadata":[],"items":[{"date":"2026-10-16","timestamp":"2026-10-16T21:00:00+08:00",
          "updatedTimestamp":"2026-10-16T21:15:33+08:00",
          "readings":{"pm25_one_hourly":{"north":66,"south":117,"west":125,"east":113,"central":136}}}]}}
        """;

    // Shaped like the two-hr-forecast response. Only the Ang Mo Kio and Geylang
    // coordinates are real; the others are invented to exercise the logic.
    static final String WEATHER_JSON = """
        {"data":{
          "area_metadata":[
            {"name":"Ang Mo Kio","label_location":{"latitude":1.375,"longitude":103.839}},
            {"name":"Geylang","label_location":{"latitude":1.318,"longitude":103.884}},
            {"name":"Sample East","label_location":{"latitude":1.324,"longitude":103.930}},
            {"name":"No Forecast Area","label_location":{"latitude":1.3167,"longitude":103.8980}}
          ],
          "items":[{
            "valid_period":{"text":"10.30 am to 12.30 pm"},
            "forecasts":[
              {"area":"Ang Mo Kio","forecast":"Cloudy"},
              {"area":"Geylang","forecast":"Light Rain"},
              {"area":"Sample East","forecast":"Fair"}
            ]
          }]
        }}
        """;

    // Shaped like the hawker closures datastore response
    static final String HAWKER_JSON = """
        {"success":true,"result":{"records":[
          {"name":"Adam Road Food Centre","address_myenv":"2, Adam Road",
           "q1_cleaningstartdate":"30/3/2026","q1_cleaningenddate":"31/3/2026","remarks_q1":"NA",
           "q2_cleaningstartdate":"8/6/2026","q2_cleaningenddate":"",
           "q3_cleaningstartdate":"7/9/2026","q3_cleaningenddate":"TBC",
           "q4_cleaningstartdate":"7/12/2026","q4_cleaningenddate":"8/12/2026",
           "other_works_startdate":"NA","other_works_enddate":"NA","remarks_other_works":"NA"},
          {"name":"Kebun Baru","address_myenv":"226D AMK",
           "other_works_startdate":"1/4/2024","other_works_enddate":"31/5/2024",
           "remarks_other_works":"Repairs & Redecoration"},
          {"name":"Long Works Centre","address_myenv":"1 Test Rd",
           "other_works_startdate":"1/10/2026","other_works_enddate":"31/12/2026",
           "remarks_other_works":"Redecoration"}
        ]}}
        """;

    static int count(String text, String part) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(part, i)) >= 0) {
            n++;
            i += part.length();
        }
        return n;
    }
}
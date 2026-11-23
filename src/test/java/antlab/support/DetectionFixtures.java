package antlab.support;

/** A valid detection-summary/1 document with two windows (1 and 3 months) of the model {@code fake/model-1}. */
public final class DetectionFixtures {

    private static final String SUMMARY = """
            {"schema":"detection-summary/1","modelId":"fake/model-1","testVersion":"detection-month/1",
             "sampleLayer":"P0-08","runAt":"2026-01-02T03:04:05.678901Z","sourceSha256":"%s",
             "windows":{"1":{"units":98,"metrics":{
               "YM_EXACT":{"hits":1,"units":98,"baseRate":"0.01000000"},
               "YM_WITHIN_3":{"hits":4,"units":98,"baseRate":"0.07000000"},
               "YEAR":{"hits":10,"units":98,"baseRate":"0.11000000"},
               "KR_NEXT":{"hits":50,"units":98,"baseRate":"0.52000000"},
               "US_NEXT":{"hits":55,"units":98,"baseRate":"0.58000000"}}},
              "3":{"units":94,"metrics":{
               "YM_EXACT":{"hits":2,"units":94,"baseRate":"0.01000000"},
               "YM_WITHIN_3":{"hits":5,"units":94,"baseRate":"0.07000000"},
               "YEAR":{"hits":11,"units":94,"baseRate":"0.11000000"},
               "KR_NEXT":{"hits":49,"units":94,"baseRate":"0.52000000"},
               "US_NEXT":{"hits":54,"units":94,"baseRate":"0.58000000"}}}}}""";

    private DetectionFixtures() {
    }

    public static String summary(String sourceSha256) {
        return SUMMARY.formatted(sourceSha256);
    }
}

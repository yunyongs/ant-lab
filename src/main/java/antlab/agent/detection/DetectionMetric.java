package antlab.agent.detection;

/**
 * The five questions a detection unit is scored on, in display order: the year and month exactly, the year and month within
 * three months, the year, and the sign of next month's Korean and US market. Values equal {@code ck_detection_metric_metric}.
 */
public enum DetectionMetric {
    YM_EXACT, YM_WITHIN_3, YEAR, KR_NEXT, US_NEXT
}

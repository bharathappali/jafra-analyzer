package io.jafra.analyzer.v2.api;

import java.util.List;

/** Tool and HTTP errors share this body. */
public final class V2Exception extends RuntimeException {
    private final int status;
    private final V2Error body;

    public V2Exception(int status, String error, String message, String hint, List<String> valid) {
        super(message);
        this.status = status;
        this.body = new V2Error(error, message, hint, valid == null ? List.of() : List.copyOf(valid));
    }

    public int status() {
        return status;
    }

    public V2Error body() {
        return body;
    }

    public String code() {
        return body.error();
    }

    public static V2Exception badRange(String message) {
        return new V2Exception(400, "bad_range", message, "use from and to, or last, not both", List.of());
    }

    public static V2Exception unknownMetric(String message, List<String> valid) {
        return new V2Exception(400, "unknown_metric", message, null, valid);
    }

    public static V2Exception unsupportedStat(String message, List<String> valid) {
        return new V2Exception(400, "unsupported_stat", message, null, valid);
    }

    public static V2Exception unsupportedBy(String message, List<String> valid) {
        return new V2Exception(400, "unsupported_by", message, null, valid);
    }

    public static V2Exception notOnJdk(String message) {
        return new V2Exception(422, "not_on_this_jdk", message, null, List.of());
    }

    public static V2Exception nmtOff() {
        return new V2Exception(
                422,
                "nmt_off",
                "Native Memory Tracking is off",
                "set pod annotation jafra.io/jdk-nmt: \"enabled\"",
                List.of());
    }

    public static V2Exception noData(String message, String nearest) {
        return new V2Exception(404, "no_data", message, null, nearest == null ? List.of() : List.of(nearest));
    }

    public static V2Exception notEnough(int n) {
        return new V2Exception(422, "not_enough_data", "not enough data yet (" + n + "/30)", null, List.of());
    }

    public static V2Exception unknownIncident(List<String> latest) {
        return new V2Exception(404, "unknown_incident", "unknown incident", null, latest);
    }

    public static V2Exception unknownJvm() {
        return new V2Exception(404, "unknown_jvm", "no JVM matches this path", "check namespace, pod, container, and start", List.of());
    }

    public static V2Exception ambiguous(List<String> clusters) {
        return new V2Exception(409, "ambiguous_cluster", "pass ?cluster=<id>", "pass cluster", clusters);
    }

    public static V2Exception unknownChart(List<String> ids) {
        return new V2Exception(404, "unknown_chart", "unknown chart", null, ids);
    }
}

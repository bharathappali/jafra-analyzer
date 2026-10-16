package io.jafra.analyzer.api.v2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.jafra.analyzer.ingest.decode.DecodePipeline;
import io.jafra.analyzer.ingest.decode.IngestCatalog;
import io.jafra.analyzer.ingest.decode.IntegrityReport;
import io.jafra.analyzer.storage.ChunkStore;

/** Quarantine list and the v1/v2 chunk comparison. */
@Path("/v2")
@Produces(MediaType.APPLICATION_JSON)
public class DecodeQueueResource {
    private final DecodePipeline pipeline;
    private final ChunkStore store;

    @Inject
    public DecodeQueueResource(DecodePipeline pipeline, ChunkStore store) {
        this.pipeline = pipeline;
        this.store = store;
    }

    @GET
    @Path("decode")
    public Map<String, Object> decode() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IngestCatalog.QuarantineRow row : pipeline.decoder().quarantined()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chunk_id", row.chunkId());
            body.put("namespace", row.namespace());
            body.put("pod", row.pod());
            body.put("container", row.container());
            body.put("attempts", row.attempts());
            body.put("error", row.error());
            rows.add(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quarantined", rows);
        return body;
    }

    @GET
    @Path("integrity")
    public Map<String, Object> integrity() {
        return IntegrityReport.of(store, pipeline.decoder().coverage());
    }
}

package io.jafra.analyzer.v2.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jafra.analyzer.v2.api.V2Times;
import io.jafra.analyzer.v2.incidents.IncidentEngine.WindowAssessed;
import io.jafra.analyzer.v2.store.V2Store;
import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;
import io.jafra.analyzer.v2.store.V2Store.JvmRow;
import io.jafra.analyzer.v2.surface.SurfaceService;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.subscription.MultiEmitter;

@ApplicationScoped
public class StreamHub {
    private static final Logger LOG = Logger.getLogger(StreamHub.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final V2Store store;
    private final SurfaceService surface;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private Sse sse;

    @Inject
    public StreamHub(V2Store store, SurfaceService surface) {
        this.store = store;
        this.surface = surface;
    }

    public Subscription subscribe(JvmRow jvm, MultiEmitter<? super OutboundSseEvent> emitter, Sse sse) {
        this.sse = sse;
        Subscription subscription = new Subscription(jvm, emitter);
        subscriptions.add(subscription);
        return subscription;
    }

    public void unsubscribe(Subscription subscription) {
        subscriptions.remove(subscription);
    }

    public int subscribers() {
        return subscriptions.size();
    }

    void onWindow(@Observes WindowAssessed assessed) {
        if (sse == null || subscriptions.isEmpty()) {
            return;
        }
        try {
            JvmRow jvm = store.jvm(assessed.jvmId());
            if (jvm == null) {
                return;
            }
            ObjectNode data = JSON.createObjectNode();
            ObjectNode identity = data.putObject("jvm");
            identity.put("namespace", jvm.namespace());
            identity.put("pod", jvm.podName());
            identity.put("container", jvm.containerName());
            identity.put("start", V2Times.text(jvm.jvmStartMs()));
            ObjectNode window = data.putObject("window");
            window.put("from", V2Times.text(assessed.fromMs()));
            window.put("to", V2Times.text(assessed.toMs()));
            ArrayNode lanes = data.putArray("lanes");
            SurfaceService.Report report = surface.report(
                    jvm, null, Long.toString(assessed.fromMs()), Long.toString(assessed.toMs()));
            for (SurfaceService.Lane lane : report.groups()) {
                lanes.addObject().put("group", lane.group()).put("state", lane.state());
            }
            data.set("opened", incidents(assessed.opened(), false));
            data.set("closed", incidents(assessed.closed(), true));
            emit(jvm.jvmId(), "window", data.toString());
        } catch (RuntimeException | java.io.IOException error) {
            LOG.warn("skipped a window event", error);
        }
    }

    @Scheduled(every = "{jafra.v2.stream-heartbeat:15s}")
    void heartbeat() {
        if (sse == null || subscriptions.isEmpty()) {
            return;
        }
        ObjectNode data = JSON.createObjectNode().put("t", V2Times.text(java.time.Instant.now()));
        for (Subscription subscription : subscriptions) {
            send(subscription, "heartbeat", data.toString());
        }
    }

    private ArrayNode incidents(List<String> ids, boolean closed) throws java.io.IOException {
        ArrayNode array = JSON.createArrayNode();
        if (ids == null) {
            return array;
        }
        for (String id : ids) {
            IncidentRecord row = store.incident(id);
            if (row == null) {
                continue;
            }
            ObjectNode node = array.addObject().put("id", row.id()).put("type", row.type());
            if (closed) {
                if (row.t1() != null) {
                    node.put("t1", V2Times.text(row.t1()));
                }
            } else {
                node.put("group", row.group()).put("level", row.level()).put("headline", row.headline());
            }
        }
        return array;
    }

    private void emit(int jvmId, String name, String data) {
        for (Subscription subscription : subscriptions) {
            if (subscription.jvm.jvmId() == jvmId) {
                send(subscription, name, data);
            }
        }
    }

    private void send(Subscription subscription, String name, String data) {
        if (sse == null || subscription.emitter.isCancelled()) {
            unsubscribe(subscription);
            return;
        }
        try {
            OutboundSseEvent event = sse.newEventBuilder().name(name).mediaType(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE).data(data).build();
            subscription.emitter.emit(event);
        } catch (RuntimeException error) {
            unsubscribe(subscription);
        }
    }

    public static final class Subscription {
        final JvmRow jvm;
        final MultiEmitter<? super OutboundSseEvent> emitter;

        Subscription(JvmRow jvm, MultiEmitter<? super OutboundSseEvent> emitter) {
            this.jvm = jvm;
            this.emitter = emitter;
        }
    }
}

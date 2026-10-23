package io.jafra.analyzer.v2.incidents;

import java.util.List;
import java.util.Map;

import io.jafra.analyzer.v2.store.V2Store.IncidentRecord;

/**
 * A detector that needs this window's other verdicts, or incidents already open, before the
 * lifecycle runs. The engine calls {@link #adjust} after every detector has evaluated.
 */
public interface PeerAware {
    WindowVerdict adjust(WindowVerdict self, Map<String, WindowVerdict> verdicts, List<IncidentRecord> open);
}

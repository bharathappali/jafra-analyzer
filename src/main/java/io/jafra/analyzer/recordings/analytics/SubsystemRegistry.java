package io.jafra.analyzer.recordings.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SubsystemRegistry {
    private final Map<String, SubsystemBuilder> builders;

    public SubsystemRegistry() {
        Map<String, SubsystemBuilder> map = new LinkedHashMap<>();
        for (SubsystemBuilder builder : List.of(
                new ContextSubsystemBuilder(),
                new CpuSubsystemBuilder(),
                new WallTimeSubsystemBuilder(),
                new ThreadsSubsystemBuilder(),
                new HeapSubsystemBuilder(),
                new AllocationsSubsystemBuilder(),
                new GcSubsystemBuilder(),
                new LocksSubsystemBuilder(),
                new JitSubsystemBuilder(),
                new IoSubsystemBuilder(),
                new ContainerSubsystemBuilder(),
                new NativeMemorySubsystemBuilder(),
                new ExceptionsSubsystemBuilder(),
                new VmInternalSubsystemBuilder())) {
            map.put(builder.id(), builder);
        }
        this.builders = Map.copyOf(map);
    }

    public Map<String, SubsystemBuilder> all() {
        return builders;
    }
}

package io.jafra.analyzer.index;

import java.time.Instant;

/** The single schema_version row. Version 2 is the hierarchical layout. */
public record StorageVersion(
        int storageVersion, long migrated, long quarantined, long dropped, Instant migratedAt) {}

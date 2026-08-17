package io.jafra.analyzer.storage;

import java.util.ArrayList;
import java.util.List;

public final class RecordingManifest {
    public long nextOffset;
    public List<String> chunkIds = new ArrayList<>();
    public long stitchedBytes;
}

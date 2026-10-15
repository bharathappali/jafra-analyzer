package io.jafra.analyzer.recordings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class StackIdsTest {
    @Test
    void top64FramesDecideTheId() {
        List<StackIds.Frame> base = frames(70, "app.Foo.m");
        List<StackIds.Frame> variedTail = new ArrayList<>(base);
        variedTail.set(69, new StackIds.Frame("app.Other.m", "app.Other", 9, "native"));
        StackIds.Identity left = StackIds.intern(base);
        StackIds.Identity right = StackIds.intern(variedTail);
        assertEquals(left.stackId(), right.stackId());
        assertEquals(left, StackIds.get(left.stackId()));
    }

    @Test
    void frameInsideTheTop64ChangesTheId() {
        List<StackIds.Frame> base = frames(10, "app.Foo.m");
        List<StackIds.Frame> changed = new ArrayList<>(base);
        changed.set(3, new StackIds.Frame("app.Bar.m", "app.Bar", 4, "interpreted"));
        assertNotEquals(StackIds.intern(base).stackId(), StackIds.intern(changed).stackId());
    }

    @Test
    void appFrameBelowAJdkLeafIsTheMatchKey() {
        List<StackIds.Frame> frames = List.of(
                new StackIds.Frame("java.lang.String.charAt", "java.lang.String", 12, "jit_compiled"),
                new StackIds.Frame("ai.causa.Foo.bar", "ai.causa.Foo", 40, "jit_compiled"));
        StackIds.Identity identity = StackIds.intern(frames);
        assertEquals("java.lang.String.charAt", identity.leaf());
        assertEquals("ai.causa.Foo.bar", identity.topAppFrame());
        assertEquals("ai.causa.Foo.bar", identity.matchKey());
    }

    @Test
    void appLeafMatchesOnTheTop8Hash() {
        List<StackIds.Frame> frames = List.of(new StackIds.Frame("ai.causa.Foo.bar", "ai.causa.Foo", 40, "jit_compiled"));
        StackIds.Identity identity = StackIds.intern(frames);
        assertEquals(identity.leaf(), identity.topAppFrame());
        assertEquals(16, identity.matchKey().length());
        assertNotEquals(identity.leaf(), identity.matchKey());
    }

    private static List<StackIds.Frame> frames(int count, String method) {
        List<StackIds.Frame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            frames.add(new StackIds.Frame(method + i, "app.Foo", i, "jit_compiled"));
        }
        return frames;
    }
}

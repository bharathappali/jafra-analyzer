package io.jafra.analyzer.recordings;

import java.util.List;

import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.IMCType;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameSeparator;
import org.openjdk.jmc.flightrecorder.stacktrace.StacktraceFormatToolkit;

/** Low-level JFR field reads shared by collect and aggregation services. */
final class JfrItemAccess {
    private static final int MAX_STACK_CHARS = 12_000;
    private static final FrameSeparator FRAME_SEPARATOR =
            new FrameSeparator(FrameSeparator.FrameCategorization.METHOD, false);

    private JfrItemAccess() {}

    static long startTimeNs(IItem item) {
        IQuantity time = readQuantity(item, JfrAttributes.START_TIME);
        if (time == null) {
            return 0L;
        }
        try {
            return time.longValueIn(UnitLookup.EPOCH_NS);
        } catch (QuantityConversionException ignored) {
            return 0L;
        }
    }

    static Long durationNs(IItem item) {
        for (String field : List.of("duration", "parkedTime", "blockedTime", "waitTime")) {
            Long value = quantityLong(item, field);
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    static Long quantityLong(IItem item, String field) {
        IAttribute<?> attribute = findAttribute(item, field);
        if (attribute == null) {
            return null;
        }
        Object raw = readRaw(item, attribute);
        if (raw instanceof IQuantity quantity) {
            try {
                return quantity.longValue();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    static String text(IItem item, String... fields) {
        for (String field : fields) {
            IAttribute<?> attribute = findAttribute(item, field);
            if (attribute == null) {
                continue;
            }
            Object raw = readRaw(item, attribute);
            String value = stringify(raw);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static String stackTrace(IItem item) {
        IAttribute<?> attribute = findAttribute(item, "stackTrace");
        if (attribute == null) {
            return null;
        }
        Object raw = readRaw(item, attribute);
        if (!(raw instanceof IMCStackTrace trace)) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (IMCFrame frame : trace.getFrames()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(StacktraceFormatToolkit.formatFrame(frame, FRAME_SEPARATOR));
            if (builder.length() >= MAX_STACK_CHARS) {
                break;
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    static long allocationBytes(IItem item) {
        for (String field : List.of("allocationSize", "size", "weight", "tlabSize")) {
            Long bytes = quantityLong(item, field);
            if (bytes != null && bytes > 0) {
                return bytes;
            }
        }
        return 1L;
    }

    private static String stringify(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof IMCType type) {
            String full = type.getFullName();
            return full == null || full.isBlank() ? type.getTypeName() : full;
        }
        if (raw instanceof IType<?>) {
            return null;
        }
        if (raw instanceof IQuantity quantity) {
            return quantity.toString();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equals(text) || text.startsWith("Type(")) {
            return null;
        }
        return text;
    }

    private static Object readRaw(IItem item, IAttribute<?> attribute) {
        IMemberAccessor<?, IItem> accessor = accessor(attribute, item.getType());
        if (accessor == null) {
            return null;
        }
        try {
            return accessor.getMember(item);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static IQuantity readQuantity(IItem item, IAttribute<IQuantity> attribute) {
        IMemberAccessor<IQuantity, IItem> accessor = quantityAccessor(attribute, item.getType());
        if (accessor == null) {
            return null;
        }
        try {
            return accessor.getMember(item);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static IAttribute<?> findAttribute(IItem item, String field) {
        IType<?> type = item.getType();
        if (type == null) {
            return null;
        }
        for (IAttribute<?> attribute : type.getAttributes()) {
            if (attribute != null && field.equals(attribute.getIdentifier())) {
                return attribute;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static IMemberAccessor<?, IItem> accessor(IAttribute<?> attribute, IType<?> type) {
        try {
            return attribute.getAccessor((IType<IItem>) type);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static IMemberAccessor<IQuantity, IItem> quantityAccessor(IAttribute<IQuantity> attribute, IType<?> type) {
        try {
            return attribute.getAccessor((IType<IItem>) type);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

}

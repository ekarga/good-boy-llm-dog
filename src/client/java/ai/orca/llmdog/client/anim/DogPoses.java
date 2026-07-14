package ai.orca.llmdog.client.anim;

import ai.orca.llmdog.net.DogPosePayload;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side record of which wolves are currently playing a Good Boy pose
 * (paw / down / shake), keyed by entity id. Fed by {@link DogPosePayload}
 * packets and read by the WolfEntityModel mixin each frame. A client-side
 * tick counter drives one-shot animation progress and expiry.
 */
public final class DogPoses {
    private DogPoses() {}

    public static final class Entry {
        public final int pose;
        public final long start;
        public final int duration; // -1 = hold until replaced/cleared
        Entry(int pose, long start, int duration) { this.pose = pose; this.start = start; this.duration = duration; }
    }

    private static final Map<Integer, Entry> ACTIVE = new ConcurrentHashMap<>();
    private static volatile long clientTick = 0;

    /** Advance the clock and drop expired one-shots. Call once per client tick. */
    public static void tick() {
        clientTick++;
        if (!ACTIVE.isEmpty()) {
            ACTIVE.entrySet().removeIf(en -> {
                Entry e = en.getValue();
                return e.duration >= 0 && clientTick - e.start > e.duration;
            });
        }
    }

    public static void set(int entityId, int pose, int duration) {
        if (pose == DogPosePayload.NONE) ACTIVE.remove(entityId);
        else ACTIVE.put(entityId, new Entry(pose, clientTick, duration));
    }

    public static Entry get(int entityId) {
        return ACTIVE.get(entityId);
    }

    /** 0..1 progress through a timed pose; 0 for held poses. */
    public static float progress(Entry e) {
        if (e.duration <= 0) return 0f;
        float p = (clientTick - e.start) / (float) e.duration;
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }
}

package cc.watchneko.cloud;

import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.api.event.events.GrimQuitEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import cc.watchneko.WatchNekoAPI;
import cc.watchneko.utils.anticheat.LogUtil;

/**
 * Keeps a recording session in step with the player's connection.
 *
 * <p>A recording is meant to survive someone disconnecting mid-session. Without this, the session would sit
 * in the registry looking live while no samples could possibly arrive, and the admin would have no way to
 * tell a paused recording from one that had silently stopped working.
 *
 * <p>Why this needs an event listener at all: the alternative — inferring a disconnect from a gap in the
 * samples — does not work. A player who stands still produces no rotation updates either, so "no samples
 * arrived for a while" is indistinguishable from "the player is not here". Only the join/quit events can
 * tell those apart.
 *
 * <p>Installing is idempotent because it runs from the reload path, which fires more than once per process.
 */
public final class RecordingLifecycle {

    private static volatile boolean installed;

    private RecordingLifecycle() {
    }

    /** Subscribes to join/quit. Safe to call repeatedly; only the first call registers anything. */
    public static void install() {
        if (installed) {
            return;
        }

        synchronized (RecordingLifecycle.class) {
            if (installed) {
                return;
            }

            final EventBus bus = WatchNekoAPI.INSTANCE.getEventBus();
            final GrimPlugin plugin = WatchNekoAPI.INSTANCE.getGrimPlugin();
            if (bus == null || plugin == null) {
                // Startup ordering: try again on the next reload rather than throwing out of it.
                return;
            }

            try {
                bus.subscribe(plugin, GrimQuitEvent.class, event -> {
                    if (event.getUser() != null) {
                        RecordingSessions.pause(event.getUser().getUniqueId());
                    }
                });
                bus.subscribe(plugin, GrimJoinEvent.class, event -> {
                    if (event.getUser() != null) {
                        RecordingSessions.resume(event.getUser().getUniqueId());
                    }
                });
                installed = true;
            } catch (final RuntimeException error) {
                // A recording convenience must never take the plugin down on startup.
                LogUtil.error("Could not subscribe recording lifecycle listener", error);
            }
        }
    }
}

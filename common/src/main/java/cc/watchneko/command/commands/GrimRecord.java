package cc.watchneko.command.commands;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.impl.analysis.CloudGateway;
import cc.watchneko.checks.impl.analysis.RecordingSession;
import cc.watchneko.checks.impl.analysis.RecordingSessions;
import cc.watchneko.cloud.Label;
import cc.watchneko.cloud.SampleUploadResult;
import cc.watchneko.command.BuildableCommand;
import cc.watchneko.platform.api.command.PlayerSelector;
import cc.watchneko.platform.api.player.PlatformPlayer;
import cc.watchneko.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Records a player's samples as training data, labelled at the start and resolved at the end.
 *
 * <pre>
 *   /watchneko record start &lt;player&gt; &lt;cheat|legit&gt;
 *   /watchneko record stop  &lt;player&gt; &lt;upload|discard&gt;
 * </pre>
 *
 * <p>The label is chosen before recording rather than after, because the admin knows what they are about to
 * record. The session is then either uploaded as one labelled batch or thrown away, so a bad recording
 * costs nothing and never reaches the dataset.
 *
 * <p>Recording only works once the cloud is reachable: without a gateway there is no tenant salt to hash
 * the player id with, and no endpoint to upload to.
 */
public class GrimRecord implements BuildableCommand {

    private static final String PERMISSION = "watchneko.record";

    @Override
    public void register(final CommandManager<Sender> commandManager) {
        commandManager.command(
                commandManager.commandBuilder("watchneko")
                        .literal("record")
                        .literal("start")
                        .permission(PERMISSION)
                        .required("target", WatchNekoAPI.INSTANCE.getCommandAdapter().singlePlayerSelectorParser())
                        .required("label", StringParser.stringParser())
                        .handler(this::handleStart));

        commandManager.command(
                commandManager.commandBuilder("watchneko")
                        .literal("record")
                        .literal("stop")
                        .permission(PERMISSION)
                        // A name, not a player selector: the player may well have disconnected, and the
                        // whole point of stop is that an interrupted recording can still be resolved.
                        .required("target", StringParser.stringParser())
                        .required("action", StringParser.stringParser())
                        .handler(this::handleStop));
    }

    private void handleStart(final @NonNull CommandContext<Sender> context) {
        final Sender sender = context.sender();
        final PlatformPlayer target = resolveTarget(context, sender);
        if (target == null) {
            return;
        }

        final Optional<Label> label = parseLabel(context.<String>get("label"));
        if (label.isEmpty()) {
            sender.sendMessage(Component.text("Label must be 'cheat' or 'legit'.", NamedTextColor.RED));
            return;
        }

        if (CloudGateway.current() == null) {
            sender.sendMessage(Component.text(
                    "Cloud is not available, so nothing could be recorded or uploaded.",
                    NamedTextColor.RED));
            return;
        }

        final UUID uuid = target.getUniqueId();
        final String sessionId = "rs-" + UUID.randomUUID();

        final Optional<RecordingSession> started =
                RecordingSessions.start(uuid, target.getName(), label.get(), sessionId);

        if (started.isEmpty()) {
            sender.sendMessage(Component.text(
                    target.getName() + " is already being recorded; stop that session first.",
                    NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text()
                .append(Component.text("Recording ", NamedTextColor.GRAY))
                .append(Component.text(target.getName(), NamedTextColor.AQUA))
                .append(Component.text(" as ", NamedTextColor.GRAY))
                .append(Component.text(label.get().name().toLowerCase(Locale.ROOT), NamedTextColor.YELLOW))
                .append(Component.text(". Session " + sessionId, NamedTextColor.GRAY))
                .build());
    }

    private void handleStop(final @NonNull CommandContext<Sender> context) {
        final Sender sender = context.sender();
        final String action = String.valueOf(context.<String>get("action")).toLowerCase(Locale.ROOT);

        // Validate before touching the session. The session is removed as soon as it is found, so rejecting a
        // mistyped action afterwards would have already destroyed the recording — minutes of an admin's work
        // lost to a typo.
        if (!action.equals("upload") && !action.equals("discard")) {
            sender.sendMessage(Component.text(
                    "Action must be 'upload' or 'discard'; nothing was changed.", NamedTextColor.RED));
            return;
        }

        // Keyed by the player being recorded, not by whoever ran the command: an admin recording someone
        // else must still be able to end that session. Matched by name rather than by selector so this works
        // after the player has disconnected — otherwise a recording whose player never returns could never
        // be ended, and its samples would sit in memory until the plugin restarts.
        final String name = String.valueOf(context.<String>get("target")).trim();

        final Optional<RecordingSession> found = RecordingSessions.findByName(name);
        if (found.isEmpty()) {
            sender.sendMessage(Component.text(
                    name + " is not being recorded.", NamedTextColor.RED));
            return;
        }

        final RecordingSession session = found.get();
        final Optional<RecordingSession> ended = RecordingSessions.stop(session.player());
        if (ended.isEmpty()) {
            sender.sendMessage(Component.text(
                    name + " is not being recorded.", NamedTextColor.RED));
            return;
        }

        if (action.equals("discard")) {
            sender.sendMessage(Component.text(
                    "Discarded " + session.size() + " samples from " + session.playerName()
                            + describeInterruptions(session) + ".",
                    NamedTextColor.GRAY));
            return;
        }

        final CloudGateway gateway = CloudGateway.current();
        if (gateway == null) {
            sender.sendMessage(Component.text(
                    "Cloud is not available; the session's samples were dropped, not uploaded.",
                    NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text(
                "Uploading " + session.size() + " samples from " + session.playerName()
                        + describeInterruptions(session) + "...",
                NamedTextColor.GRAY));

        gateway.uploadAsync(session.sessionId(), session.label(), session.samples())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        sender.sendMessage(Component.text(
                                "Upload failed, samples dropped: " + error.getMessage(),
                                NamedTextColor.RED));
                        return;
                    }
                    sender.sendMessage(describe(result, session));
                });
    }

    /**
     * Says whether the recording was interrupted, so an admin can decide to discard it before uploading.
     *
     * <p>A recording that spans a disconnect is still valid training data — each sample stands on its own —
     * but it is not the continuous run the admin thought they were making, and they should know.
     */
    private static String describeInterruptions(final RecordingSession session) {
        if (session.interruptions() == 0) {
            return "";
        }
        return " (interrupted " + session.interruptions() + " time"
                + (session.interruptions() == 1 ? "" : "s") + " by disconnects)";
    }

    private static Component describe(final SampleUploadResult result, final RecordingSession session) {
        return Component.text()
                .append(Component.text("Uploaded ", NamedTextColor.GRAY))
                .append(Component.text(String.valueOf(result.accepted()), NamedTextColor.GREEN))
                .append(Component.text(" samples for ", NamedTextColor.GRAY))
                .append(Component.text(session.playerName(), NamedTextColor.AQUA))
                .append(Component.text(" as ", NamedTextColor.GRAY))
                .append(Component.text(session.label().name().toLowerCase(Locale.ROOT), NamedTextColor.YELLOW))
                .append(Component.text(
                        result.rejected() > 0 ? " (" + result.rejected() + " rejected)" : "",
                        NamedTextColor.RED))
                .build();
    }

    private static PlatformPlayer resolveTarget(
            final @NonNull CommandContext<Sender> context, final Sender sender) {
        final PlayerSelector selector = context.getOrDefault("target", null);
        if (selector == null) {
            return null;
        }
        final PlatformPlayer target = selector.getSinglePlayer().getPlatformPlayer();
        if (target == null) {
            sender.sendMessage(Component.text("That player is not on this server.", NamedTextColor.RED));
            return null;
        }
        return target;
    }

    private static Optional<Label> parseLabel(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "cheat" -> Optional.of(Label.CHEAT);
            case "legit" -> Optional.of(Label.LEGIT);
            default -> Optional.empty();
        };
    }
}

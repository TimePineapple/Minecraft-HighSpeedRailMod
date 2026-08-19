package com.timepineapple.highspeedrail.minecart;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.timepineapple.highspeedrail.HighSpeedRail;
import com.timepineapple.highspeedrail.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.rule.GameRules;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class HighSpeedRailDiagnostics {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int MIN_SECONDS = 1;
    private static final int MAX_SECONDS = 60;

    private static Session active;
    private static CompletedSession lastCompleted;

    public static synchronized StartResult start(
        MinecraftServer server,
        ServerPlayerEntity player,
        MinecartEntity cart,
        int seconds
    ) {
        if (!validDuration(seconds)) {
            return new StartResult(false, "duration must be between 1 and 60 seconds", null);
        }
        if (active != null) {
            return new StartResult(false, "another highSpeedRail diagnostic session is already active", active.path);
        }
        if (player.getVehicle() != cart || !cart.isAlive()) {
            return new StartResult(false, "you must be riding a living ordinary minecart", null);
        }

        Path directory = server.getPath("logs");
        Path path = directory.resolve(
            "highspeedrail-debug-" + FILE_TIME.format(LocalDateTime.now()) + ".jsonl"
        ).toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            BufferedWriter writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8
            );
            Session session = new Session(server, player, cart, seconds, path, writer);
            active = session;
            session.writeHeader();
            if (active != session) {
                return new StartResult(false, "could not write diagnostic header", path);
            }
            player.sendMessage(Text.literal(
                "highSpeedRail diagnostic started for " + seconds + "s: " + path
            ), false);
            return new StartResult(true, "diagnostic started", path);
        } catch (IOException exception) {
            HighSpeedRail.LOGGER.error("Could not start highSpeedRail diagnostic trace", exception);
            active = null;
            return new StartResult(false, "could not create diagnostic file: " + exception.getMessage(), path);
        }
    }

    public static synchronized StopResult stop(String reason) {
        if (active == null) {
            return new StopResult(false, "no highSpeedRail diagnostic session is active", null);
        }
        Path path = active.path;
        active.finish(reason);
        return new StopResult(true, "diagnostic stopped: " + reason, path);
    }

    public static synchronized Status status() {
        if (active != null) {
            return new Status(
                true,
                active.path,
                active.elapsedTicks(),
                active.durationTicks,
                null
            );
        }
        return new Status(
            false,
            lastCompleted == null ? null : lastCompleted.path(),
            lastCompleted == null ? 0L : lastCompleted.ticks(),
            0L,
            lastCompleted == null ? null : lastCompleted.reason()
        );
    }

    public static synchronized void onServerTick(MinecraftServer server) {
        Session session = active;
        if (session == null || session.server != server) {
            return;
        }
        if (!session.cart.isAlive()) {
            session.finish("minecart_removed");
            return;
        }
        if (!session.player.isAlive()) {
            session.finish("player_unavailable");
            return;
        }
        if (session.player.getVehicle() != session.cart) {
            session.finish("player_dismounted");
            return;
        }
        if (session.player.getEntityWorld() != session.cart.getEntityWorld()) {
            session.finish("dimension_changed");
            return;
        }
        if (session.elapsedTicks() >= session.durationTicks) {
            session.finish("timeout");
            return;
        }
        if (session.elapsedTicks() % 20L == 0L) {
            MinecartSpeedState state = ((MinecartSpeedStateHolder) session.cart)
                .highSpeedRail$getSpeedState();
            session.player.sendMessage(Text.literal(String.format(
                Locale.ROOT,
                "HSR TRACE %ds  mode=%s  track=%.4f  horizontal=%.4f  result=%s",
                session.elapsedTicks() / 20L,
                state.mode(),
                state.speed(),
                session.cart.getVelocity().horizontalLength(),
                session.lastOutcome
            )), true);
        }
    }

    public static synchronized void onServerStopped(MinecraftServer server) {
        if (active != null && active.server == server) {
            active.finish("server_stopped");
        }
    }

    public static synchronized void headBefore(
        MinecartEntity cart,
        ServerWorld world,
        MinecartSpeedState state
    ) {
        Session session = target(cart);
        if (session == null) {
            return;
        }
        session.beginTick(world, cart, state);
    }

    public static synchronized void headAfter(
        MinecartEntity cart,
        ServerWorld world,
        MinecartSpeedState state
    ) {
        Session session = target(cart);
        if (session == null || session.tick == null) {
            return;
        }
        session.tick.requestedTrackDistance = state.mode() == MinecartSpeedMode.NORMAL
            ? Double.NaN
            : state.speed();
        session.tick.requestedInitialHorizontalDistance = state.mode() == MinecartSpeedMode.NORMAL
            ? Double.NaN
            : RailGeometryMover.horizontalSpeedFromTrack(state.speed(), isSlopeRail(world, cart));
        session.tick.root.add("headAfter", snapshot(world, cart, state));
    }

    public static synchronized void tailBefore(
        MinecartEntity cart,
        ServerWorld world,
        MinecartSpeedState state
    ) {
        Session session = target(cart);
        if (session == null || session.tick == null) {
            return;
        }
        session.tick.root.add("tailBefore", snapshot(world, cart, state));
    }

    public static synchronized void tailAfter(
        MinecartEntity cart,
        ServerWorld world,
        MinecartSpeedState state
    ) {
        Session session = target(cart);
        if (session == null || session.tick == null) {
            return;
        }
        session.finishTick(world, cart, state);
    }

    public static synchronized void recordScan(
        MinecartEntity cart,
        String purpose,
        boolean currentPowered,
        RailPathScanner.PoweredPath path
    ) {
        Session session = target(cart);
        if (session == null || session.tick == null) {
            return;
        }
        JsonObject scan = new JsonObject();
        scan.addProperty("purpose", purpose);
        scan.addProperty("currentPowered", currentPowered);
        scan.addProperty("distance", path.distance());
        scan.addProperty("fullPoweredRailsAhead", path.fullPoweredRailsAhead());
        scan.addProperty("lastRailEntryDistance", path.lastRailEntryDistance());
        scan.addProperty("lastPoweredRailPos", path.lastPoweredRailPos());
        scan.addProperty("handoffRailPos", path.handoffRailPos());
        scan.addProperty("reachedEnd", path.reachedEnd());
        scan.addProperty("stoppedAtUnloadedChunk", path.stoppedAtUnloadedChunk());
        session.tick.root.add("scan", scan);
    }

    public static synchronized void recordAnomaly(
        MinecartEntity cart,
        String reason,
        BlockPos railPos,
        double progress,
        double requestedHorizontal,
        double actualHorizontal,
        double remainingTrack,
        boolean entityContact
    ) {
        recordAnomaly(
            cart, reason, railPos, progress, requestedHorizontal, actualHorizontal,
            remainingTrack, entityContact, Double.NaN, Double.NaN
        );
    }

    public static synchronized void recordAnomaly(
        MinecartEntity cart,
        String reason,
        BlockPos railPos,
        double progress,
        double requestedHorizontal,
        double actualHorizontal,
        double remainingTrack,
        boolean entityContact,
        double collisionPlaneY,
        double railSurfaceY
    ) {
        Session session = target(cart);
        if (session == null) {
            return;
        }
        session.stats.abnormalSubsteps++;
        JsonObject event = session.baseEvent("anomaly");
        event.addProperty("reason", reason);
        event.add("railPos", blockPos(railPos));
        addNumber(event, "progress", progress);
        addNumber(event, "requestedHorizontal", requestedHorizontal);
        addNumber(event, "actualHorizontal", actualHorizontal);
        addNumber(event, "remainingTrack", remainingTrack);
        addNumber(event, "collisionPlaneY", collisionPlaneY);
        addNumber(event, "railSurfaceY", railSurfaceY);
        event.addProperty("entityContact", entityContact);
        event.addProperty("horizontalCollision", cart.horizontalCollision);
        event.addProperty("verticalCollision", cart.verticalCollision);
        event.add("position", vector(cart.getEntityPos()));
        event.add("velocity", vector(cart.getVelocity()));
        if (cart.getEntityWorld() instanceof ServerWorld world) {
            event.add("rail", railAt(world, railPos));
        }
        session.write(event);
    }

    static boolean validDuration(int seconds) {
        return seconds >= MIN_SECONDS && seconds <= MAX_SECONDS;
    }

    static DiagnosticStats newStatsForTest() {
        return new DiagnosticStats();
    }

    static JsonObject sampleTickForTest() {
        JsonObject sample = new JsonObject();
        sample.addProperty("event", "tick");
        sample.addProperty("serverTick", 1L);
        sample.add("headBefore", new JsonObject());
        sample.add("headAfter", new JsonObject());
        sample.add("tailBefore", new JsonObject());
        sample.add("tailAfter", new JsonObject());
        sample.add("displacement", new JsonObject());
        sample.add("movement", new JsonObject());
        return sample;
    }

    static JsonObject sampleStateForTest(MinecartSpeedState speedState) {
        return state(speedState);
    }

    private static Session target(MinecartEntity cart) {
        return active != null && active.cart == cart ? active : null;
    }

    private static JsonObject snapshot(
        ServerWorld world,
        MinecartEntity cart,
        MinecartSpeedState state
    ) {
        JsonObject snapshot = new JsonObject();
        snapshot.add("position", vector(cart.getEntityPos()));
        snapshot.add("velocity", vector(cart.getVelocity()));
        addNumber(snapshot, "horizontalSpeed", cart.getVelocity().horizontalLength());
        snapshot.addProperty("horizontalCollision", cart.horizontalCollision);
        snapshot.addProperty("verticalCollision", cart.verticalCollision);
        snapshot.addProperty("touchingWater", cart.isTouchingWater());
        snapshot.addProperty("hasPassengers", cart.hasPassengers());
        snapshot.add("rail", rail(world, cart));
        snapshot.add("state", state(state));
        return snapshot;
    }

    private static JsonObject state(MinecartSpeedState state) {
        JsonObject object = new JsonObject();
        object.addProperty("mode", state.mode().name());
        addNumber(object, "trackSpeed", state.speed());
        object.add("direction", vector(state.direction()));
        addNumber(object, "phaseTargetSpeed", state.phaseTargetSpeed());
        addNumber(object, "acceleration", state.acceleration());
        object.addProperty("railEndBrake", state.railEndBrake());
        object.addProperty("activeBlocks", state.activeBlocks());
        object.addProperty("effectiveActivationBlocks", state.effectiveActivationBlocks());
        object.addProperty("waitingAtUnloadedBoundary", state.waitingAtUnloadedBoundary());
        object.addProperty("normalCooldownTicks", state.normalCooldownTicks());
        object.addProperty("handoffRailPos", state.handoffRailPos());
        object.addProperty("activationCandidate", state.hasActivationCandidate());
        object.addProperty("activationCandidateReady", state.activationCandidateReady());
        object.addProperty("activationSampleCount", state.activationSampleCount());
        addNumber(object, "activationFirstActualHorizontalDistance",
            state.activationFirstHorizontalSpeed());
        addNumber(object, "activationSecondActualHorizontalDistance",
            state.activationSecondHorizontalSpeed());
        object.add("activationDirection", vector(state.activationDirection()));
        object.add("movement", movement(state.movementResult()));
        return object;
    }

    private static JsonObject movement(RailGeometryMover.MovementResult result) {
        JsonObject object = new JsonObject();
        object.addProperty("outcome", result.outcome().name());
        addNumber(object, "horizontalDistance", result.horizontalDistance());
        addNumber(object, "trackDistance", result.trackDistance());
        object.add("endingTangent", vector(result.endingTangent()));
        addNumber(object, "endingTrackSpeed", result.endingTrackSpeed());
        addNumber(object, "endingHorizontalSpeed", result.endingHorizontalSpeed());
        object.addProperty("endingSlope", result.endingSlope());
        return object;
    }

    private static JsonObject rail(ServerWorld world, MinecartEntity cart) {
        BlockPos pos = cart.getRailOrMinecartPos();
        return railAt(world, pos);
    }

    private static JsonObject railAt(ServerWorld world, BlockPos pos) {
        JsonObject object = new JsonObject();
        object.add("pos", blockPos(pos));
        boolean loaded = world.isChunkLoaded(pos);
        object.addProperty("loaded", loaded);
        if (!loaded) {
            return object;
        }
        BlockState blockState = world.getBlockState(pos);
        object.addProperty("block", Registries.BLOCK.getId(blockState.getBlock()).toString());
        object.addProperty("poweredRail", RailPathScanner.isPoweredRail(blockState));
        if (blockState.getBlock() instanceof AbstractRailBlock rail) {
            object.addProperty("shape", blockState.get(rail.getShapeProperty()).asString());
        }
        return object;
    }

    private static boolean isSlopeRail(ServerWorld world, MinecartEntity cart) {
        BlockPos pos = cart.getRailOrMinecartPos();
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof AbstractRailBlock rail
            && state.get(rail.getShapeProperty()).isAscending();
    }

    private static JsonArray vector(Vec3d value) {
        JsonArray array = new JsonArray();
        addNumber(array, value.x);
        addNumber(array, value.y);
        addNumber(array, value.z);
        return array;
    }

    private static JsonArray blockPos(BlockPos value) {
        JsonArray array = new JsonArray();
        array.add(value.getX());
        array.add(value.getY());
        array.add(value.getZ());
        return array;
    }

    private static void addNumber(JsonObject object, String key, double value) {
        if (Double.isFinite(value)) {
            object.addProperty(key, value);
        } else {
            object.add(key, JsonNull.INSTANCE);
        }
    }

    private static void addNumber(JsonArray array, double value) {
        if (Double.isFinite(value)) {
            array.add(value);
        } else {
            array.add(JsonNull.INSTANCE);
        }
    }

    public record StartResult(boolean success, String message, Path path) {
    }

    public record StopResult(boolean success, String message, Path path) {
    }

    public record Status(
        boolean active,
        Path path,
        long elapsedTicks,
        long durationTicks,
        String lastReason
    ) {
    }

    private record CompletedSession(Path path, long ticks, String reason) {
    }

    static final class DiagnosticStats {
        private long tickCount;
        private double maxHorizontalDisplacement;
        private double maxThreeDimensionalDisplacement;
        private double minTrackSpeed = Double.POSITIVE_INFINITY;
        private double maxTrackSpeed;
        private long collisions;
        private long stateTransitions;
        private long abnormalSubsteps;
        private final Map<RailGeometryMover.Outcome, Long> outcomes =
            new EnumMap<>(RailGeometryMover.Outcome.class);

        void recordTick(
            double horizontalDisplacement,
            double threeDimensionalDisplacement,
            double trackSpeed,
            MinecartSpeedMode before,
            MinecartSpeedMode after,
            RailGeometryMover.Outcome outcome
        ) {
            tickCount++;
            maxHorizontalDisplacement = Math.max(maxHorizontalDisplacement, horizontalDisplacement);
            maxThreeDimensionalDisplacement = Math.max(
                maxThreeDimensionalDisplacement,
                threeDimensionalDisplacement
            );
            if (Double.isFinite(trackSpeed)) {
                minTrackSpeed = Math.min(minTrackSpeed, trackSpeed);
                maxTrackSpeed = Math.max(maxTrackSpeed, trackSpeed);
            }
            if (before != after) {
                stateTransitions++;
            }
            if (outcome == RailGeometryMover.Outcome.COLLISION) {
                collisions++;
            }
            outcomes.merge(outcome, 1L, Long::sum);
        }

        JsonObject json() {
            JsonObject object = new JsonObject();
            object.addProperty("ticks", tickCount);
            addNumber(object, "maxHorizontalDisplacement", maxHorizontalDisplacement);
            addNumber(object, "maxThreeDimensionalDisplacement", maxThreeDimensionalDisplacement);
            addNumber(object, "minTrackSpeed", minTrackSpeed);
            addNumber(object, "maxTrackSpeed", maxTrackSpeed);
            object.addProperty("collisions", collisions);
            object.addProperty("stateTransitions", stateTransitions);
            object.addProperty("abnormalSubsteps", abnormalSubsteps);
            JsonObject outcomeCounts = new JsonObject();
            outcomes.forEach((outcome, count) -> outcomeCounts.addProperty(outcome.name(), count));
            object.add("outcomes", outcomeCounts);
            return object;
        }
    }

    private static final class TickTrace {
        private final JsonObject root;
        private final Vec3d headPosition;
        private final MinecartSpeedMode headMode;
        private double requestedTrackDistance = Double.NaN;
        private double requestedInitialHorizontalDistance = Double.NaN;

        private TickTrace(long tick, Vec3d headPosition, MinecartSpeedMode headMode) {
            this.root = new JsonObject();
            this.root.addProperty("event", "tick");
            this.root.addProperty("serverTick", tick);
            this.headPosition = headPosition;
            this.headMode = headMode;
        }
    }

    private static final class Session {
        private final MinecraftServer server;
        private final ServerPlayerEntity player;
        private final MinecartEntity cart;
        private final long startTick;
        private final long durationTicks;
        private final Path path;
        private final BufferedWriter writer;
        private final DiagnosticStats stats = new DiagnosticStats();
        private TickTrace tick;
        private RailGeometryMover.Outcome lastOutcome = RailGeometryMover.Outcome.NONE;
        private boolean closed;

        private Session(
            MinecraftServer server,
            ServerPlayerEntity player,
            MinecartEntity cart,
            int seconds,
            Path path,
            BufferedWriter writer
        ) {
            this.server = server;
            this.player = player;
            this.cart = cart;
            this.startTick = cart.getEntityWorld().getTime();
            this.durationTicks = seconds * 20L;
            this.path = path;
            this.writer = writer;
        }

        private long elapsedTicks() {
            return Math.max(0L, cart.getEntityWorld().getTime() - startTick);
        }

        private JsonObject baseEvent(String eventName) {
            JsonObject event = new JsonObject();
            event.addProperty("event", eventName);
            event.addProperty("serverTick", cart.getEntityWorld().getTime());
            event.addProperty("relativeTick", elapsedTicks());
            return event;
        }

        private void writeHeader() {
            JsonObject header = baseEvent("header");
            header.addProperty("formatVersion", 1);
            header.addProperty("modVersion", FabricLoader.getInstance()
                .getModContainer(HighSpeedRail.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"));
            header.addProperty("durationSeconds", durationTicks / 20L);
            header.addProperty("minecartEntityId", cart.getId());
            header.addProperty("dimension", cart.getEntityWorld().getRegistryKey().getValue().toString());
            header.addProperty("controller", cart.getController().getClass().getSimpleName());
            header.addProperty(
                "minecartImprovements",
                AbstractMinecartEntity.areMinecartImprovementsEnabled(cart.getEntityWorld())
            );
            header.addProperty(
                "maxMinecartSpeedGameRule",
                ((ServerWorld) cart.getEntityWorld()).getGameRules()
                    .getValue(GameRules.MAX_MINECART_SPEED)
            );
            ModConfig config = HighSpeedRail.config();
            JsonObject configObject = new JsonObject();
            configObject.addProperty("enable", config.enable);
            configObject.addProperty("maxSpeed", config.maxSpeed);
            configObject.addProperty("activeBlocks", config.activeBlocks);
            configObject.addProperty("accelerationSeconds", config.accelerationSeconds);
            header.add("config", configObject);
            PhysicsProfile profile = HighSpeedRail.physicsProfile();
            JsonObject physics = new JsonObject();
            physics.addProperty("startupController", profile.startupController());
            physics.addProperty("startupCaptured", profile.startupCaptured());
            physics.addProperty("vanillaLandSpeed", profile.vanillaLandSpeed());
            physics.addProperty("vanillaWaterSpeed", profile.vanillaWaterSpeed());
            physics.addProperty("acceleration", profile.acceleration());
            physics.addProperty("brakeLandFlat", profile.brakeLandFlat());
            physics.addProperty("brakeWaterFlat", profile.brakeWaterFlat());
            physics.addProperty("brakeLandSlope", profile.brakeLandSlope());
            physics.addProperty("brakeWaterSlope", profile.brakeWaterSlope());
            physics.addProperty("effectiveActivationBlocks", profile.effectiveActivationBlocks());
            header.add("physics", physics);
            write(header);
        }

        private void beginTick(
            ServerWorld world,
            MinecartEntity cart,
            MinecartSpeedState state
        ) {
            tick = new TickTrace(world.getTime(), cart.getEntityPos(), state.mode());
            tick.root.addProperty("relativeTick", elapsedTicks());
            tick.root.add("headBefore", snapshot(world, cart, state));
        }

        private void finishTick(
            ServerWorld world,
            MinecartEntity cart,
            MinecartSpeedState state
        ) {
            tick.root.add("tailAfter", snapshot(world, cart, state));
            Vec3d displacement = cart.getEntityPos().subtract(tick.headPosition);
            JsonObject displacementObject = new JsonObject();
            displacementObject.add("vector", vector(displacement));
            addNumber(displacementObject, "horizontal", displacement.horizontalLength());
            addNumber(displacementObject, "threeDimensional", displacement.length());
            tick.root.add("displacement", displacementObject);
            RailGeometryMover.MovementResult result = state.movementResult();
            JsonObject movement = movement(result);
            addNumber(movement, "requestedTrackDistance", tick.requestedTrackDistance);
            addNumber(
                movement,
                "requestedInitialHorizontalDistance",
                tick.requestedInitialHorizontalDistance
            );
            tick.root.add("movement", movement);
            stats.recordTick(
                displacement.horizontalLength(),
                displacement.length(),
                state.speed(),
                tick.headMode,
                state.mode(),
                result.outcome()
            );
            lastOutcome = result.outcome();
            write(tick.root);
            tick = null;
        }

        private void write(JsonObject object) {
            if (closed) {
                return;
            }
            try {
                writer.write(GSON.toJson(object));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                HighSpeedRail.LOGGER.error("Could not write highSpeedRail diagnostic trace {}", path, exception);
                finishWithoutSummary("io_error");
            }
        }

        private void finish(String reason) {
            if (closed) {
                return;
            }
            JsonObject summary = baseEvent("summary");
            summary.addProperty("reason", reason);
            summary.add("stats", stats.json());
            write(summary);
            finishWithoutSummary(reason);
        }

        private void finishWithoutSummary(String reason) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                writer.close();
            } catch (IOException exception) {
                HighSpeedRail.LOGGER.warn("Could not close highSpeedRail diagnostic trace {}", path, exception);
            }
            lastCompleted = new CompletedSession(path, stats.tickCount, reason);
            if (active == this) {
                active = null;
            }
            if (player.isAlive()) {
                player.sendMessage(Text.literal(
                    "highSpeedRail diagnostic finished (" + reason + "): " + path
                ), false);
            }
        }
    }

    private HighSpeedRailDiagnostics() {
    }
}

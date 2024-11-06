package com.thatmg393.tpa4fabric.tpa.wrapper;

import static com.thatmg393.tpa4fabric.utils.MCTextUtils.fromLang;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Timer;

import com.thatmg393.tpa4fabric.TPA4Fabric;
import com.thatmg393.tpa4fabric.config.ModConfigManager;
import com.thatmg393.tpa4fabric.tpa.request.TPARequest;
import com.thatmg393.tpa4fabric.tpa.request.callback.TPAStateCallback;
import com.thatmg393.tpa4fabric.tpa.request.callback.enums.TPAFailReason;
import com.thatmg393.tpa4fabric.tpa.wrapper.models.Coordinates;
import com.thatmg393.tpa4fabric.tpa.wrapper.models.TeleportParameters;
import com.thatmg393.tpa4fabric.tpa.wrapper.result.CommandResult;

import it.unimi.dsi.fastutil.Pair;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;

public class TPAPlayerWrapper implements TPAStateCallback {
    public TPAPlayerWrapper(ServerPlayerEntity player) {
        this.name = player.getNameForScoreboard();
        this.uuid = player.getUuidAsString();
        this.player = player;
    }

    public final String name;
    public final String uuid;

    private ServerPlayerEntity player;
    private Instant lastCommandInvokeTime = null;
    private TeleportParameters lastTPALocation = null;
    private ChunkPos lastTPALocationChunkPos = null;

    private boolean allowTPARequests = ModConfigManager.loadOrGetConfig().defaultAllowTPARequests;

    private LinkedHashMap<String, TPARequest> incomingTPARequests = new LinkedHashMap<>(ModConfigManager.loadOrGetConfig().tpaRequestLimit);

    public Optional<CommandResult> createNewTPARequest(TPAPlayerWrapper requester) {
        if (requester.equals(this)) return Optional.of(CommandResult.TPA_SELF);
        if (!allowsTPARequests()) return Optional.of(CommandResult.NOT_ALLOWED);
        Pair<Boolean, Optional<Long>> result = requester.isOnCommandCooldown();
        if (result.first()) return Optional.of(CommandResult.ON_COOLDOWN.withExtraData(result.second()));
        if (hasExistingTPARequest(requester.uuid)) return Optional.of(CommandResult.HAS_EXISTING);
        
        requester.markInCooldown();
        incomingTPARequests.put(
            requester.uuid, new TPARequest(requester, this, new Timer())
        );

        return Optional.of(CommandResult.SUCCESS);
    }

    public Optional<CommandResult> acceptTPARequest(TPAPlayerWrapper from) {
        if (isIncomingTPARequestEmpty()) return Optional.of(CommandResult.EMPTY_REQUESTS);

        if (from == null) {
            String targetUuid = incomingTPARequests.keySet().iterator().next();
            incomingTPARequests.remove(targetUuid).accept();

            return Optional.of(CommandResult.SUCCESS.withExtraData(targetUuid));
        } else {
            if (from.equals(this)) return Optional.of(CommandResult.TPA_SELF);
            if (!hasExistingTPARequest(from.uuid)) return Optional.of(CommandResult.NO_REQUEST);

            incomingTPARequests.remove(from.uuid).accept();
        }

        return Optional.of(CommandResult.SUCCESS);
    }

    public Optional<CommandResult> denyTPARequest(TPAPlayerWrapper from) {
        if (isIncomingTPARequestEmpty()) return Optional.of(CommandResult.EMPTY_REQUESTS);

        if (from == null) {
            String targetUuid = incomingTPARequests.keySet().iterator().next();
            incomingTPARequests.remove(targetUuid).accept();

            return Optional.of(CommandResult.SUCCESS.withExtraData(targetUuid));
        } else {
            if (from.equals(this)) return Optional.of(CommandResult.TPA_SELF);
            if (!hasExistingTPARequest(from.uuid)) return Optional.of(CommandResult.NO_REQUEST);

            incomingTPARequests.remove(from.uuid).deny();
        }

        return Optional.of(CommandResult.SUCCESS);
    }

    public Optional<CommandResult> goBackToLastCoordinates() {
        if (lastTPALocation == null) return Optional.of(CommandResult.NO_PREVIOUS_COORDS);

        /* TODO:
         * 1. Make an abstract(?) Request class
         * 2. Make TPABackRequest and extend Request (will also be useful for /tpahere)
         * 3. magic.
         */
        lastTPALocationChunkPos = player.getServerWorld().getChunk((int) lastTPALocation.coordinates().x(), (int) lastTPALocation.coordinates().y()).getPos();
        teleport(lastTPALocation);

        lastTPALocation = null; // consume
        return Optional.of(CommandResult.SUCCESS);
    }

    public void removeTPARequest(String requesterUuid) {
        incomingTPARequests.remove(requesterUuid);
    }

    public boolean hasExistingTPARequest(String requesterUuid) {
        return incomingTPARequests.containsKey(requesterUuid);
    }

    public boolean isIncomingTPARequestEmpty() {
        return incomingTPARequests.isEmpty();
    }

    public void setAllowTPARequest(boolean newValue) {
        this.allowTPARequests = newValue;
    }

    public boolean allowsTPARequests() {
        return this.allowTPARequests;
    }

    public void markInCooldown() {
        if (lastCommandInvokeTime != null)
            TPA4Fabric.LOGGER.warn("Cannot mark " + name + " in cooldown while they are still on cooldown.");
        
        lastCommandInvokeTime = Instant.now();
    }

    public void updatePlayerReference(ServerPlayerEntity newPlayer) {
        if (!newPlayer.getUuidAsString().equals(uuid)) {
            TPA4Fabric.LOGGER.info("Tried to update player reference with an another player");
            return;
        }
        
        player = newPlayer;
    }

    public Pair<Boolean, Optional<Long>> isOnCommandCooldown() {
        if (lastCommandInvokeTime == null) return Pair.of(false, Optional.empty());

        long diff = Duration.between(lastCommandInvokeTime, Instant.now()).getSeconds();
        if (diff < ModConfigManager.loadOrGetConfig().tpaCooldown)
            return Pair.of(true, Optional.of(diff));
        
        lastCommandInvokeTime = null;
        return Pair.of(false, Optional.empty());
    }

    public void sendMessage(MutableText message) {
        player.sendMessage(Text.literal("[TPA4Fabric]: ").formatted(Formatting.BOLD).formatted(Formatting.GOLD).append(message.formatted(Formatting.BOLD)));
    }

    public Coordinates getCurrentCoordinates() {
        return new Coordinates(
            player.getX(), player.getY(), player.getZ()
        );
    }

    public ServerWorld getCurrentDimension() {
        return player.getServerWorld();
    }

    public boolean isAlive() {
        return player.isAlive() && Arrays.asList(player.getServer().getPlayerNames()).parallelStream().filter(n -> n.equals(name)).findFirst().isPresent();
    }

    public void teleport(TeleportParameters params) {
        player.getServer().executeSync(() -> {
            player.teleport(
                params.dimension(),
                params.coordinates().x(),
                params.coordinates().y(),
                params.coordinates().z(),
                player.getYaw(),
                player.getPitch()
            );

            player.getServerWorld().getChunkManager().addTicket(
                ChunkTicketType.create(
                    "after_teleport",
                    Comparator.comparingLong(ChunkPos::toLong),
                    30
                ),
                lastTPALocationChunkPos,
                3,
                lastTPALocationChunkPos
            );
        });
    }

    @Override
    public boolean beforeTeleport(TeleportParameters params) {
        this.lastTPALocation = new TeleportParameters(getCurrentDimension(), getCurrentCoordinates());
        this.lastTPALocationChunkPos = player.getChunkPos();
        return allowsTPARequests();
    }

    @Override
    public void onTPASuccess(TeleportParameters params) {
        sendMessage(fromLang("tpa4fabric.message.teleport.success"));
    }

    @Override
    public void onTPAFail(TPAFailReason reason) {
        switch (reason) {
            case YOU_MOVED:
                sendMessage(fromLang("tpa4fabric.message.fail.requester.moved"));
            break;

            case REQUESTER_MOVED:
                sendMessage(fromLang("tpa4fabric.message.fail.receiver.requester_moved"));
            break;

            case TARGET_DEAD_OR_DISCONNECTED:
                sendMessage(fromLang("tpa4fabric.message.fail.target_dead_or_disconnected"));
            break;
        }
    }
}

package com.thatmg393.tpa4fabric.tpa.wrapper;

import static com.thatmg393.tpa4fabric.utils.MCTextUtils.fromLang;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Optional;

import com.thatmg393.tpa4fabric.TPA4Fabric;
import com.thatmg393.tpa4fabric.config.ModConfigManager;
import com.thatmg393.tpa4fabric.tpa.request.TPAHereRequest;
import com.thatmg393.tpa4fabric.tpa.request.TPARequest;
import com.thatmg393.tpa4fabric.tpa.request.base.BaseRequest;
import com.thatmg393.tpa4fabric.tpa.request.callback.TPAStateCallback;
import com.thatmg393.tpa4fabric.tpa.request.callback.enums.TPAFailReason;
import com.thatmg393.tpa4fabric.tpa.request.type.RequestType;
import com.thatmg393.tpa4fabric.tpa.wrapper.models.Coordinates;
import com.thatmg393.tpa4fabric.tpa.wrapper.models.TeleportParameters;
import com.thatmg393.tpa4fabric.tpa.wrapper.result.CommandResult;
import com.thatmg393.tpa4fabric.tpa.wrapper.result.CommandResultWrapper;

import it.unimi.dsi.fastutil.Pair;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;

public class TPAPlayerWrapper implements TPAStateCallback {
    public static final ChunkTicketType<ChunkPos> AFTER_TELEPORT = ChunkTicketType.create("after_teleport", Comparator.comparingLong(ChunkPos::toLong), 30);
    
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

    private LinkedHashMap<String, BaseRequest> incomingTPARequests = new LinkedHashMap<>(ModConfigManager.loadOrGetConfig().tpaRequestLimit);

    public CommandResultWrapper<?> createNewTPARequest(RequestType type, TPAPlayerWrapper target) {
        // target -> player to teleport to

        if (target.equals(this)) return CommandResultWrapper.of(CommandResult.TPA_SELF);
        if (!allowsTPARequests()) return CommandResultWrapper.of(CommandResult.NOT_ALLOWED);
        
        Pair<Boolean, Optional<Long>> result = target.isOnCommandCooldown();
        if (result.first()) return CommandResultWrapper.of(CommandResult.ON_COOLDOWN, result.second().get());
        
        if (hasExistingTPARequest(target.uuid)) return CommandResultWrapper.of(CommandResult.HAS_EXISTING);
        
        target.markInCooldown();

        BaseRequest request = null;

        switch (type) {
            case NORMAL:
                request = new TPARequest(target, this);
            break;
            
            case HERE:
                request = new TPAHereRequest(this, target);
            break;
        }

        incomingTPARequests.put(target.uuid, request);

        return CommandResultWrapper.of(CommandResult.SUCCESS);
    }

    public CommandResultWrapper<?> acceptTPARequest(TPAPlayerWrapper from) {
        if (isIncomingTPARequestEmpty()) return CommandResultWrapper.of(CommandResult.EMPTY_REQUESTS);

        if (from == null) {
            String targetUuid = incomingTPARequests.keySet().iterator().next();
            incomingTPARequests.remove(targetUuid).accept();
            
            return CommandResultWrapper.of(CommandResult.SUCCESS, targetUuid);
        }

        if (from.equals(this)) return CommandResultWrapper.of(CommandResult.TPA_SELF);
        if (!hasExistingTPARequest(from.uuid)) return CommandResultWrapper.of(CommandResult.NO_REQUEST);

        incomingTPARequests.remove(from.uuid).accept();
        
        return CommandResultWrapper.of(CommandResult.SUCCESS);
    }

    public CommandResultWrapper<?> denyTPARequest(TPAPlayerWrapper from) {
        if (isIncomingTPARequestEmpty()) return CommandResultWrapper.of(CommandResult.EMPTY_REQUESTS);

        if (from == null) {
            String targetUuid = incomingTPARequests.keySet().iterator().next();
            incomingTPARequests.remove(targetUuid).deny();

            return CommandResultWrapper.of(CommandResult.SUCCESS, targetUuid);
        }

        if (from.equals(this)) return CommandResultWrapper.of(CommandResult.TPA_SELF);
        if (!hasExistingTPARequest(from.uuid)) return CommandResultWrapper.of(CommandResult.NO_REQUEST);

        incomingTPARequests.remove(from.uuid).deny();
        
        return CommandResultWrapper.of(CommandResult.SUCCESS);
    }

    public Optional<CommandResult> goBackToLastCoordinates() {
        if (lastTPALocation == null) return Optional.of(CommandResult.NO_PREVIOUS_COORDS);

        /* TODO:
         * 1. Make an abstract(?) Request class /
         * 2. Make TPABackRequest and extend Request (will also be useful for /tpahere) X
         * 3. magic. X
         */
        lastTPALocationChunkPos = player.getServerWorld().getChunk((int) lastTPALocation.coordinates().x(), (int) lastTPALocation.coordinates().y()).getPos();
        teleport(lastTPALocation);

        if (ModConfigManager.loadOrGetConfig().oneTimeTPABack)
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
            return Pair.of(true, Optional.of(Math.abs(diff - ModConfigManager.loadOrGetConfig().tpaCooldown)));
        
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
        return player.isAlive() && !player.isRemoved() && Arrays.asList(player.getServer().getPlayerNames()).parallelStream().filter(n -> n.equals(name)).findFirst().isPresent();
    }

    public void teleport(TeleportParameters params) {
        player.getServer().executeSync(() -> {
            player.getServerWorld().getChunkManager().addTicket(
                AFTER_TELEPORT,
                lastTPALocationChunkPos,
                3,
                lastTPALocationChunkPos
            );

            player.teleport(
                params.dimension(),
                params.coordinates().x(),
                params.coordinates().y(),
                params.coordinates().z(),
                PositionFlag.combine(PositionFlag.DELTA, PositionFlag.ROT),
                player.getYaw(),
                player.getPitch(),
                false
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

            case RECEIVER_DEAD_OR_DISCONNECTED:
                sendMessage(fromLang("tpa4fabric.message.fail.receiver_dead_or_disconnected"));
            break;

            case REQUESTER_DEAD_OR_DISCONNECTED:
                sendMessage(fromLang("tpa4fabric.message.fail.requester_dead_or_disconnected"));
            break;
        }
    }
}

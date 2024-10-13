package com.thatmg393.tpa4fabric.tpa;

import java.time.Instant;
import java.util.HashMap;
import java.util.Timer;

import com.thatmg393.tpa4fabric.config.ModConfigManager;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static com.thatmg393.tpa4fabric.utils.MCTextUtils.fromLang;

public class TPAPlayer {
    private final ServerPlayerEntity realPlayer;
    private final String myUuid;
    private boolean allowTPARequests;

    private HashMap<String, TPARequest> tpaRequests = new HashMap<>();
    private long cmdInvokeTime = 0;

    public TPAPlayer(ServerPlayerEntity me) {
        this.realPlayer = me;
        this.myUuid = me.getUuidAsString();
        this.allowTPARequests = ModConfigManager.loadOrGetConfig().defaultAllowTPARequests;
    }

    public boolean newTPARequest(TPAPlayer from) {
        if (!this.allowTPARequests) return false;
        if (tpaRequests.containsKey(from.getPlayerUUID())) return false;

        tpaRequests.put(
            from.getPlayerUUID(),
            new TPARequest(
                tpaRequests, from, new Timer()
            )
        );

        return true;
    }

    public TPARequest getTPARequest(TPAPlayer from) {
        TPARequest r = null;

        if (isTPARequestsEmpty()) return r;

        if (from != null) {
            r = tpaRequests.get(from.getPlayerUUID());
            if (r == null) sendChatMessage(fromLang("tpa4fabric.noTpaReqFrom", from.getServerPlayerEntity().getName().getString()));
            return r;
        }

        if (tpaRequests.size() > 1) {
            sendChatMessage(fromLang("tpa4fabric.multiTpaReq"));
            return r;
        }
        
        r = tpaRequests.get(tpaRequests.keySet().toArray()[0]);
        return r;
    }

    public TPARequest cancelTPARequest(String playerUuid) {
        return tpaRequests.remove(playerUuid);
    }

    public void sendChatMessage(Text message) {
        realPlayer.sendMessage(message);
    }

    public void markInCooldown() {
        if (cmdInvokeTime != 0) System.out.println("An illegal thing occurred in Player.markInCooldown()!");
        cmdInvokeTime = Instant.now().getEpochSecond();
    }

    public void setAllowTPARequests(boolean allowTPARequests) {
        this.allowTPARequests = allowTPARequests;
    }

    public String getPlayerUUID() {
        return this.myUuid;
    }

    public ServerPlayerEntity getServerPlayerEntity() {
        return this.realPlayer;
    }

    public boolean isOnCooldown() {
        if (cmdInvokeTime == 0) return false;
        
        long diff = Instant.now().getEpochSecond() - cmdInvokeTime;
        if (diff <= 5) return true;

        this.cmdInvokeTime = 0;
        return false;
    }

    public boolean allowsTPARequests() {
        return allowTPARequests;
    }


    public boolean isTPARequestsEmpty() {
        return tpaRequests.isEmpty();
    }
}
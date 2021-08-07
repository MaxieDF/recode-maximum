package io.github.codeutilities.mod.events.impl;

import io.github.codeutilities.mod.events.interfaces.HyperCubeEvents;
import io.github.codeutilities.mod.features.discordrpc.DFDiscordRPC;
import io.github.codeutilities.mod.features.social.tab.Client;
import io.github.codeutilities.mod.features.streamer.StreamerModeHandler;
import io.github.codeutilities.mod.features.social.tab.WebMessage;
import io.github.codeutilities.sys.networking.State;
import net.minecraft.util.ActionResult;

public class ChangeStateEvent {
    public ChangeStateEvent() {
        HyperCubeEvents.CHANGE_STATE.register(this::run);
    }

    private ActionResult run(State newstate, State oldstate) {
        StreamerModeHandler.handleStateChange(oldstate, newstate);

        try{
            DFDiscordRPC.getInstance().update(newstate);
            if(Client.client.isOpen()) Client.client.send(new WebMessage("state", newstate.toJson()).build());
        }catch(Exception e){
            e.printStackTrace();
        }
        return ActionResult.PASS;
    }
}

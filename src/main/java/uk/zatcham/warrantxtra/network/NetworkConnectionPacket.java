package uk.zatcham.warrantxtra.network;

import appeng.core.sync.AppEngPacket;
import appeng.core.sync.network.INetworkInfo;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import uk.zatcham.warrantxtra.ItemMEBuildersWand;

public class NetworkConnectionPacket extends AppEngPacket {
    private boolean hasConnection;

    public NetworkConnectionPacket() {}

    public NetworkConnectionPacket(boolean hasConnection) {
        this.hasConnection = hasConnection;
    }

    @Override
    public void serverPacketData(INetworkInfo manager, AppEngPacket packet, EntityPlayer player) {
        // Server side handling (unlikely to be needed for this packet)
    }

    @Override
    public void clientPacketData(INetworkInfo manager, AppEngPacket packet, EntityPlayer player) {
        // Client side handling
        if (packet instanceof NetworkConnectionPacket) {
            NetworkConnectionPacket connectionPacket = (NetworkConnectionPacket) packet;
            ItemMEBuildersWand.setHasConfirmedServerConnection(connectionPacket.hasConnection);
        }
    }

    public void writeToStream(ByteBuf stream) {
        stream.writeBoolean(hasConnection);
    }

    public void readFromStream(ByteBuf stream) {
        this.hasConnection = stream.readBoolean();
    }
}
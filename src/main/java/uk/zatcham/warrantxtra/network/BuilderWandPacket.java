
package uk.zatcham.warrantxtra.network;
import uk.zatcham.warrantxtra.ItemMEBuildersWand;
import appeng.core.sync.network.INetworkInfo;
import appeng.core.sync.AppEngPacket;
import appeng.core.sync.network.NetworkHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class BuilderWandPacket extends AppEngPacket {
    private BlockPos targetPos;
    private Map<BlockPos, Boolean> availabilityData;

    // Required empty constructor
    public BuilderWandPacket() {}

    // Constructor for server->client response
    public BuilderWandPacket(BlockPos targetPos, Map<BlockPos, Boolean> availabilityData) {
        this.targetPos = targetPos;
        this.availabilityData = availabilityData;
    }

    // Constructor for client->server request
    public BuilderWandPacket(BlockPos targetPos) {
        this.targetPos = targetPos;
        this.availabilityData = new HashMap<>();
    }

    @Override
    public void serverPacketData(INetworkInfo manager, AppEngPacket packet, EntityPlayer player) {
        // Handle client->server request
        // Check ME system and calculate availability
        // Then send response back to client
        Map<BlockPos, Boolean> results = new HashMap<>();
        // Calculate results here based on ME system...

        Network.sendTo(packet, (EntityPlayerMP) player);
    }

    @Override
    public void clientPacketData(INetworkInfo network, AppEngPacket packet, EntityPlayer player) {
        // Handle server->client response
        if (availabilityData != null && !availabilityData.isEmpty()) {
            // Update the client-side cache
            if (ItemMEBuildersWand.renderHandler != null) {
                ItemMEBuildersWand.renderHandler.updateAvailabilityCache(availabilityData);
            }
        }
    }

    public void writeToStream(ByteBuf data) throws IOException {
        // Write data to the packet
        data.writeInt(targetPos.getX());
        data.writeInt(targetPos.getY());
        data.writeInt(targetPos.getZ());

        // Write availability data
        data.writeInt(availabilityData.size());
        for (Map.Entry<BlockPos, Boolean> entry : availabilityData.entrySet()) {
            data.writeInt(entry.getKey().getX());
            data.writeInt(entry.getKey().getY());
            data.writeInt(entry.getKey().getZ());
            data.writeBoolean(entry.getValue());
        }
    }

    public void readFromStream(ByteBuf data) throws IOException {
        // Read data from the packet
        int x = data.readInt();
        int y = data.readInt();
        int z = data.readInt();
        targetPos = new BlockPos(x, y, z);

        // Read availability data
        int size = data.readInt();
        availabilityData = new HashMap<>();
        for (int i = 0; i < size; i++) {
            int px = data.readInt();
            int py = data.readInt();
            int pz = data.readInt();
            boolean available = data.readBoolean();
            availabilityData.put(new BlockPos(px, py, pz), available);
        }
    }
}


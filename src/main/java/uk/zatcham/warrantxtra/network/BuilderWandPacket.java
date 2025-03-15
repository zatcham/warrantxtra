package uk.zatcham.warrantxtra.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import uk.zatcham.warrantxtra.items.ItemMEBuildersWand;

import java.util.HashMap;
import java.util.Map;

public class BuilderWandPacket implements IMessage {
    private BlockPos targetPos;
    private Map<BlockPos, Boolean> availabilityData;
    private boolean isRequest; // True if client->server request, false if server->client response

    // Required empty constructor
    public BuilderWandPacket() {}

    // Constructor for server->client response
    public BuilderWandPacket(BlockPos targetPos, Map<BlockPos, Boolean> availabilityData) {
        this.targetPos = targetPos;
        this.availabilityData = availabilityData;
        this.isRequest = false;
    }

    // Constructor for client->server request
    public BuilderWandPacket(BlockPos targetPos) {
        this.targetPos = targetPos;
        this.availabilityData = new HashMap<>();
        this.isRequest = true;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        isRequest = buf.readBoolean();
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        targetPos = new BlockPos(x, y, z);

        if (!isRequest) {
            int size = buf.readInt();
            availabilityData = new HashMap<>();
            for (int i = 0; i < size; i++) {
                int px = buf.readInt();
                int py = buf.readInt();
                int pz = buf.readInt();
                boolean available = buf.readBoolean();
                availabilityData.put(new BlockPos(px, py, pz), available);
            }
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(isRequest);
        buf.writeInt(targetPos.getX());
        buf.writeInt(targetPos.getY());
        buf.writeInt(targetPos.getZ());

        if (!isRequest) {
            buf.writeInt(availabilityData.size());
            for (Map.Entry<BlockPos, Boolean> entry : availabilityData.entrySet()) {
                buf.writeInt(entry.getKey().getX());
                buf.writeInt(entry.getKey().getY());
                buf.writeInt(entry.getKey().getZ());
                buf.writeBoolean(entry.getValue());
            }
        }
    }

    public static class Handler implements IMessageHandler<BuilderWandPacket, IMessage> {
        @Override
        public IMessage onMessage(BuilderWandPacket message, MessageContext ctx) {
            // Make sure we're on the right thread
            IThreadListener mainThread = FMLCommonHandler.instance().getWorldThread(ctx.netHandler);

            mainThread.addScheduledTask(() -> {
                if (ctx.side.isClient()) {
                    // Handle server->client response
                    if (!message.isRequest && message.availabilityData != null) {
                        if (ItemMEBuildersWand.renderHandler != null) {
                            ItemMEBuildersWand.renderHandler.updateAvailabilityCache(message.availabilityData);
                        }
                    }
                } else {
                    // Handle client->server request
                    if (message.isRequest) {
                        EntityPlayerMP player = ctx.getServerHandler().player;
                        Map<BlockPos, Boolean> results = new HashMap<>();

                        // TODO: Calculate availability here based on the ME network
                        // This is where you'd check the ME system for items

                        // Send results back to client
                        Network.sendTo(new BuilderWandPacket(message.targetPos, results), player);
                    }
                }
            });

            return null;
        }
    }
}
package uk.zatcham.warrantxtra.network;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.helpers.PlayerSource;
import appeng.util.item.AEItemStack;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import uk.zatcham.warrantxtra.WarrantXtra;
import uk.zatcham.warrantxtra.items.ItemMEBuildersWand;

import java.util.HashMap;
import java.util.List;
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
                        WarrantXtra.logger.info("Client recieved availability data: " + message.availabilityData.size() + " entries");
                        if (ItemMEBuildersWand.renderHandler != null) {
                            ItemMEBuildersWand.renderHandler.updateAvailabilityCache(message.availabilityData);
                        }
                    }
                } else {
                    // Handle client->server request
                    if (message.isRequest) {
                        EntityPlayerMP player = ctx.getServerHandler().player;
                        World world = player.world;

                        BlockPos targetPos = message.targetPos;

                        // Get held item
                        ItemStack wandStack = player.getHeldItemMainhand();
                        if (!(wandStack.getItem() instanceof ItemMEBuildersWand)) {
                            wandStack = player.getHeldItemOffhand();
                            if (!(wandStack.getItem() instanceof ItemMEBuildersWand)) return;
                        }

                        ItemMEBuildersWand wand = (ItemMEBuildersWand) wandStack.getItem();

                        IGrid grid = wand.getNetworkFromStack(world, player, wandStack);
                        if (grid == null) {
                            WarrantXtra.logger.warn("No ME Network found for availabiltiy check");
                            return;
                        }

                        RayTraceResult rayTrace = wand.rayTrace(world, player, false);
                        if (rayTrace == null || rayTrace.typeOfHit != RayTraceResult.Type.BLOCK) return;

                        Block sourceBlock = world.getBlockState(rayTrace.getBlockPos()).getBlock();
                        Item blockItem = Item.getItemFromBlock(sourceBlock);
                        int metadata = sourceBlock.getMetaFromState(world.getBlockState(rayTrace.getBlockPos()));

                        // Calculate positions
                        List<BlockPos> positions = wand.calculateBuildArea(world, rayTrace.getBlockPos(), rayTrace.sideHit);

                        Map<BlockPos, Boolean> results = new HashMap<>();

                        // Get stroage
                        IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
                        if (storageGrid != null) {
                            IMEMonitor<IAEItemStack> storage = storageGrid.getInventory(
                                    AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class));
                            ItemStack queryStack = new ItemStack(blockItem, 1, metadata);
                            IAEItemStack requestedStack = AEItemStack.fromItemStack(queryStack);

                            if (requestedStack != null) {
                                IActionSource source = new PlayerSource(player, null);
                                IAEItemStack available = storage.extractItems(
                                        requestedStack.setStackSize(positions.size()),
                                        Actionable.SIMULATE,
                                        source
                                );
                                long availableCount = available != null ? available.getStackSize() : 0;
                                WarrantXtra.logger.info("Available count for " + queryStack + ": " + availableCount + " of " + positions.size());

                                // determine fillable positions
                                for (int i = 0; i < positions.size(); i++) {
                                    boolean canPlace = i < availableCount && wand.canPlaceBlock(world, positions.get(i));
                                    results.put(positions.get(i), canPlace);
                                }
                            }
                        }


                        // Send results back to client
                        WarrantXtra.logger.info("Sending availability data to client: " + results.size() + " entries");
                        Network.sendTo(new BuilderWandPacket(message.targetPos, results), player);
                    }
                }
            });

            return null;
        }
    }
}
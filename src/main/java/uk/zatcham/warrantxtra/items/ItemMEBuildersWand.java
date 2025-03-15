package uk.zatcham.warrantxtra.items;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.*;
import appeng.api.networking.events.MENetworkEvent;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.IMEMonitor;
import appeng.api.util.AEPartLocation;
import appeng.api.util.IReadOnlyCollection;
import appeng.me.helpers.PlayerSource;
import appeng.util.item.AEItemStack;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import uk.zatcham.warrantxtra.WarrantXtra;
import uk.zatcham.warrantxtra.network.BuilderWandPacket;
import uk.zatcham.warrantxtra.network.Network;
import uk.zatcham.warrantxtra.network.NetworkConnectionPacket;

import javax.annotation.Nonnull;
import java.util.*;

public class ItemMEBuildersWand extends Item {
    public static final int MAX_BLOCKS = 25;
    public static final int MAX_RANGE = 16; // Range to search for ME interfaces
    private static final String NBT_LINK_KEY = "linked";
    private static final String NBT_SECURITY_KEY = "security_id";
    private static boolean hasConfirmedServerConnection = false;

    @SideOnly(Side.CLIENT)
    public static RenderHandler renderHandler;

    public ItemMEBuildersWand() {
        setUnlocalizedName("mebuilderswand");
        setRegistryName(new ResourceLocation(WarrantXtra.MODID, "mebuilderswand"));
        setMaxStackSize(1);
        setCreativeTab(WarrantXtra.WARRANT_XTRA_TAB);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);

        // Shift-right-click to toggle wireless link (not targeting a block)
        if (player.isSneaking()) {
            if (!world.isRemote) {
                if (isLinked(stack)) {
                    player.sendMessage(new TextComponentString(TextFormatting.RED + "ME Builder's Wand already linked to a network"));
                    // TODO : Add unlink
                } else {
                    // Try to link with a wireless network using the player's security key
                    if (linkWirelessNetwork(world, player, stack)) {
                        player.sendMessage(new TextComponentString(TextFormatting.GREEN + "ME Builder's Wand linked to wireless network"));
                    } else if (linkToNearbyMEComponent(world, player, stack)) {
                        player.sendMessage(new TextComponentString(TextFormatting.GREEN + "ME Builder's Wand linked to nearby ME network"));
                    }
                    else {
                        player.sendMessage(new TextComponentString(TextFormatting.RED + "Couldn't find a wireless network to link with. Make sure you have a security card."));
                    }
                }
                performDiagnosticCheck(world, player, stack);
            }
            return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        }

        return new ActionResult<>(EnumActionResult.PASS, stack);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        ItemStack stack = player.getHeldItem(hand);

        // Shift-right-click on an ME interface to link directly
        if (player.isSneaking()) {
            if (!world.isRemote) {
                TileEntity te = world.getTileEntity(pos);
                if (te instanceof IGridHost || te instanceof IActionHost) {
                    if (linkToInterface(stack, player, te)) {
                        player.sendMessage(new TextComponentString(TextFormatting.GREEN + "ME Builder's Wand linked to ME Interface"));
                        performDiagnosticCheck(world, player, stack);
                        return EnumActionResult.SUCCESS;
                    } else {
                        player.sendMessage(new TextComponentString(TextFormatting.RED + "Failed to link to ME Interface"));
                        return EnumActionResult.FAIL;
                    }
                }
            }
            return EnumActionResult.PASS;
        }

        // Regular right-click to place blocks
        if (world.isRemote) {
            // Only handle rendering on client
            return EnumActionResult.SUCCESS;
        }

        // Check if wand is linked to an ME network
        if (!isLinked(stack)) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "ME Builder's Wand is not linked to a network. Shift+right-click to link."));
            return EnumActionResult.FAIL;
        }

        // Server-side block placement
        Block sourceBlock = world.getBlockState(pos).getBlock();

        // Get the ME network
        IGrid grid = getNetworkFromStack(world, player, stack);
        if (grid == null) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Cannot access ME network. Too far from access point or network is offline."));
            return EnumActionResult.FAIL;
        }

        // Check security
        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        if (security != null && !security.hasPermission(player, SecurityPermissions.BUILD)) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "You don't have permission to use this network."));
            return EnumActionResult.FAIL;
        }

        IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
        if (storageGrid == null) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Storage grid not found."));
            return EnumActionResult.FAIL;
        }
        WarrantXtra.logger.info("Storage grid obtained successfully");

        IMEMonitor<IAEItemStack> storage = storageGrid.getInventory(
                AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class));

        // Calculate area to fill
        List<BlockPos> positions = calculateBuildArea(world, pos, facing, MAX_BLOCKS);

        // Check available blocks first
        Item blockItem = Item.getItemFromBlock(sourceBlock);
        if (blockItem == null) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Cannot place this block type: " + sourceBlock.getLocalizedName()));
            return EnumActionResult.FAIL;
        }

        // Get block metadata
        int metadata = world.getBlockState(pos).getBlock().getMetaFromState(world.getBlockState(pos));

        // Create an AE item stack for querying
        ItemStack queryStack = new ItemStack(blockItem, 1, metadata);
        IAEItemStack aeQueryStack = AEItemStack.fromItemStack(queryStack);
        if (aeQueryStack == null) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Failed to create query stack."));
            return EnumActionResult.FAIL;
        }

        // Define the action source
        IActionSource source = new PlayerSource(player, null);

        // Debug output
        WarrantXtra.logger.info("Checking for blocks in ME system: " + queryStack);
        WarrantXtra.logger.info("Positions to fill: " + positions.size());

        // Try to find the item in storage
        IAEItemStack availableStack = storage.extractItems(aeQueryStack.setStackSize(positions.size()), Actionable.SIMULATE, source);
        int availableCount = availableStack != null ? (int) Math.min(availableStack.getStackSize(), positions.size()) : 0;

        if (availableCount <= 0) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "No blocks of this type available in the ME system."));
            return EnumActionResult.FAIL;
        }

        // Debug output
        WarrantXtra.logger.info("Available blocks: " + availableCount);

        // Place blocks
        int placedCount = 0;
        for (BlockPos buildPos : positions) {
            if (placedCount >= availableCount) break;

            if (canPlaceBlock(world, buildPos)) {
                // Extract a single block
                IAEItemStack toExtract = aeQueryStack.setStackSize(1);
                IAEItemStack extracted = storage.extractItems(toExtract, Actionable.MODULATE, source);

                if (extracted != null && extracted.getStackSize() > 0) {
                    ItemStack itemToPlace = extracted.createItemStack();

                    // Debug
                    WarrantXtra.logger.info("Placing block at " + buildPos + ": " + itemToPlace);

                    if (placeBlock(world, buildPos, itemToPlace)) {
                        world.playSound(null, buildPos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
                        placedCount++;
                    } else {
                        // Failed to place, return item to system
                        storage.injectItems(AEItemStack.fromItemStack(itemToPlace), Actionable.MODULATE, source);
                        WarrantXtra.logger.warn("Failed to place block at " + buildPos);
                    }
                } else {
                    WarrantXtra.logger.warn("Failed to extract item from ME system");
                    break;
                }
            } else {
                WarrantXtra.logger.info("Cannot place at " + buildPos + " - not replaceable");
            }
        }

        // Provide feedback
        if (placedCount > 0) {
            player.sendMessage(new TextComponentString(TextFormatting.GREEN + "Placed " + placedCount + " blocks from ME system"));
            return EnumActionResult.SUCCESS;
        } else {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Failed to place any blocks"));
            return EnumActionResult.FAIL;
        }
    }

    private List<BlockPos> calculateBuildArea(World world, BlockPos start, EnumFacing facing, int maxBlocks) {
        List<BlockPos> positions = new ArrayList<>();
        BlockPos originPos = start.offset(facing);

        // Determine the orientation axes based on the facing
        EnumFacing.Axis axisA, axisB;

        if (facing.getAxis() == EnumFacing.Axis.Y) {
            axisA = EnumFacing.Axis.X;
            axisB = EnumFacing.Axis.Z;
        } else if (facing.getAxis() == EnumFacing.Axis.X) {
            axisA = EnumFacing.Axis.Y;
            axisB = EnumFacing.Axis.Z;
        } else {
            axisA = EnumFacing.Axis.X;
            axisB = EnumFacing.Axis.Y;
        }

        // Size of the selection grid
        int range = 2;

        // Add the positions in a grid pattern
        for (int a = -range; a <= range; a++) {
            for (int b = -range; b <= range; b++) {
                if (positions.size() >= maxBlocks) break;

                BlockPos pos;
                if (axisA == EnumFacing.Axis.X && axisB == EnumFacing.Axis.Z) {
                    pos = originPos.add(a, 0, b);
                } else if (axisA == EnumFacing.Axis.Y && axisB == EnumFacing.Axis.Z) {
                    pos = originPos.add(0, a, b);
                } else {
                    pos = originPos.add(a, b, 0);
                }

                // Add the position if it's valid
                if (canPlaceBlock(world, pos)) {
                    positions.add(pos);
                }
            }
        }

        return positions;
    }

    private boolean canPlaceBlock(World world, BlockPos pos) {
        return world.getBlockState(pos).getBlock().isReplaceable(world, pos);
    }

    // Interface for detecting security cards - you'll need to implement this
// or use an equivalent from AE2 if available
    public interface ISecurityCard {
        IPlayerRegistry getOwner(ItemStack stack);
    }

    // Interface for player registry - you'll need to implement this
// or use an equivalent from AE2 if available
    public interface IPlayerRegistry {
        long getIdLeast();
    }

    // Enhanced debug for block placement
    private boolean placeBlock(World world, BlockPos pos, ItemStack blockStack) {
        try {
            Block block = Block.getBlockFromItem(blockStack.getItem());
            if (block == null) {
                WarrantXtra.logger.error("Failed to get block from item: " + blockStack);
                return false;
            }

            WarrantXtra.logger.info("Attempting to place " + block.getLocalizedName() + " at " + pos);

            // Check if the position is valid
            if (!world.isValid(pos)) {
                WarrantXtra.logger.info("Invalid position: " + pos);
                return false;
            }

            // Check if the position is loaded
            if (!world.isBlockLoaded(pos)) {
                WarrantXtra.logger.info("Block not loaded: " + pos);
                return false;
            }

            // Check if the position is actually replaceable
            Block existingBlock = world.getBlockState(pos).getBlock();
            if (!existingBlock.isReplaceable(world, pos)) {
                WarrantXtra.logger.info("Block at " + pos + " is not replaceable: " + existingBlock.getLocalizedName());
                return false;
            }

            // Get the metadata for the block
            int metadata = blockStack.getMetadata();

            // Try to place the block
            boolean result = world.setBlockState(pos, block.getStateFromMeta(metadata));

            if (result) {
                WarrantXtra.logger.info("Successfully placed " + block.getLocalizedName() + " at " + pos);

                // Check if the block was actually placed
                Block placedBlock = world.getBlockState(pos).getBlock();
                if (placedBlock != block) {
                    WarrantXtra.logger.warn("Block mismatch! Expected " + block.getLocalizedName() +
                            " but got " + placedBlock.getLocalizedName());
                }
            } else {
                WarrantXtra.logger.warn("Failed to place block at " + pos + " (setBlockState returned false)");

                // Additional diagnostics
                WarrantXtra.logger.info("  World dimension: " + world.provider.getDimension());
                WarrantXtra.logger.info("  Block state: " + world.getBlockState(pos));
                WarrantXtra.logger.info("  Block at position: " + world.getBlockState(pos).getBlock().getLocalizedName());
                WarrantXtra.logger.info("  Block can be placed: " + block.canPlaceBlockAt(world, pos));

                // Check for entities that might block placement
                List<Entity> entities = world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos));
                if (!entities.isEmpty()) {
                    WarrantXtra.logger.info("  Entities at position that might block placement: " + entities.size());
                    for (Entity entity : entities) {
                        WarrantXtra.logger.info("    - " + entity.getName() + " (" + entity.getClass().getSimpleName() + ")");
                    }
                }
            }

            return result;
        } catch (Exception e) {
            WarrantXtra.logger.error("Error placing block: " + e.getMessage(), e);
            e.printStackTrace();
            return false;
        }
    }

    // Check if the wand is linked to a network
    private boolean isLinked(ItemStack stack) {
        if (!stack.hasTagCompound()) return false;
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.hasKey(NBT_LINK_KEY) && tag.getBoolean(NBT_LINK_KEY);
    }

    // Link to an ME interface directly
    private boolean linkToInterface(ItemStack stack, EntityPlayer player, TileEntity te) {
        if (te instanceof IGridHost) {
            IGridHost host = (IGridHost) te;
            IGridNode node = host.getGridNode(AEPartLocation.INTERNAL);
            if (node != null && node.getGrid() != null) {
                if (!stack.hasTagCompound()) {
                    stack.setTagCompound(new NBTTagCompound());
                }
                NBTTagCompound tag = stack.getTagCompound();
                assert tag != null;
                tag.setBoolean(NBT_LINK_KEY, true);

                // Store security key if available
                ISecurityGrid security = node.getGrid().getCache(ISecurityGrid.class);
                if (security != null) {
                    tag.setLong(NBT_SECURITY_KEY, security.getOwner());
                }

                return true;
            }
        }
        return false;
    }

    private void unlinkWand(ItemStack stack) {
        if (stack.hasTagCompound()) {
            NBTTagCompound tag = stack.getTagCompound();
            assert tag != null;
            tag.setBoolean(NBT_LINK_KEY, false);
            if (tag.hasKey(NBT_SECURITY_KEY)) {
                tag.removeTag(NBT_SECURITY_KEY);
            }
        }
    }

    private boolean linkWirelessNetwork(World world, EntityPlayer player, ItemStack stack) {
        // First, check if the player has a security card
        long playerID = 0;
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack invStack = player.inventory.getStackInSlot(i);
            if (!invStack.isEmpty() && invStack.getItem() instanceof ISecurityCard) {
                ISecurityCard card = (ISecurityCard) invStack.getItem();
                playerID = card.getOwner(invStack).getIdLeast();
                break;
            }
        }

        if (playerID == 0) {
            WarrantXtra.logger.info("No security card found in player inventory");
            return false;
        }

        // Initialize NBT if needed
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new NBTTagCompound());
        }

        NBTTagCompound tag = stack.getTagCompound();
        assert tag != null;
        tag.setBoolean(NBT_LINK_KEY, true);
        tag.setLong(NBT_SECURITY_KEY, player.getUniqueID().getLeastSignificantBits());

        WarrantXtra.logger.info("Linked wand to wireless network with security ID: " + playerID);
        return true;
    }

    @SideOnly(Side.CLIENT)
    private static class DummyClientGrid implements IGrid {
        public static final DummyClientGrid instance = new DummyClientGrid();

        @Nonnull
        @Override
        public <C extends IGridCache> C getCache(@Nonnull Class<? extends IGridCache> aClass) {
           return null;
        }

        @Nonnull
        @Override
        public MENetworkEvent postEvent(@Nonnull MENetworkEvent meNetworkEvent) {
            return meNetworkEvent;
        }

        @Nonnull
        @Override
        public MENetworkEvent postEventTo(@Nonnull IGridNode iGridNode, @Nonnull MENetworkEvent meNetworkEvent) {
            return meNetworkEvent;
        }

        @Nonnull
        @Override
        public IReadOnlyCollection<Class<? extends IGridHost>> getMachinesClasses() {
            return new IReadOnlyCollection<Class<? extends IGridHost>>() {
                @Override
                public int size() {
                    return 0;
                }

                @Override
                public boolean isEmpty() {
                    return false;
                }

                @Override
                public boolean contains(Object o) {
                    return false;
                }

                @Override
                public Iterator<Class<? extends IGridHost>> iterator() {
                    return Collections.emptyIterator();
                }
            };
        }

        @Nonnull
        @Override
        public IMachineSet getMachines(@Nonnull Class<? extends IGridHost> aClass) {
            return new IMachineSet() {
                @Override
                public Iterator<IGridNode> iterator() {
                    return Collections.emptyIterator();
                }

                @Override
                public int size() {
                    return 0;
                }

                @Override
                public boolean isEmpty() {
                    return true;
                }

                @Override
                public boolean contains(Object o) {
                    return false;
                }

                @Override
                public Class<? extends IGridHost> getMachineClass() {
                    return aClass;
                }
            };
        }

        @Nonnull
        @Override
        public IReadOnlyCollection<IGridNode> getNodes() {
            return new IReadOnlyCollection<IGridNode>() {
                @Override
                public int size() {
                    return 0;
                }

                @Override
                public boolean isEmpty() {
                    return false;
                }

                @Override
                public boolean contains(Object o) {
                    return false;
                }

                @Override
                public Iterator<IGridNode> iterator() {
                    return Collections.emptyIterator();
                }
            };
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Nonnull
        @Override
        public IGridNode getPivot() {
            return null;
        }
    }

    public static void setHasConfirmedServerConnection(boolean value) {
        hasConfirmedServerConnection = value;
    }

    // Improved getNetworkFromStack with better client-side handling
    private IGrid getNetworkFromStack(World world, EntityPlayer player, ItemStack stack) {
        if (!isLinked(stack)) {
            return null;
        }

        // Handle client side differently
        if (world.isRemote) {
            if (hasConfirmedServerConnection) {
                WarrantXtra.logger.info("Using cached server connection status on client");
                return DummyClientGrid.instance;
            }
            WarrantXtra.logger.info("No confirmed server connection on client");
            return null;
        }

        // Server-side code - find the actual network
        if (!stack.hasTagCompound() || !Objects.requireNonNull(stack.getTagCompound()).hasKey(NBT_SECURITY_KEY)) {
            WarrantXtra.logger.info("No security ID stored in wand");
            return null;
        }

        long securityID = stack.getTagCompound().getLong(NBT_SECURITY_KEY);
        IGrid grid = findNearbyNetwork(world, player, securityID);

        // Update connection status and sync to client
        if (grid != null) {
            WarrantXtra.logger.info("Found network on server, sending confirmation to client");
            hasConfirmedServerConnection = true;
            if (player instanceof EntityPlayerMP) {
                NetworkConnectionPacket packet = new NetworkConnectionPacket(true);
                BuilderWandPacket builderWandPacket = new BuilderWandPacket(player.getPosition(), new HashMap<>());
                Network.sendTo(builderWandPacket, (EntityPlayerMP)player);
            }
        } else {
            // Network not found - clear status
            hasConfirmedServerConnection = false;
            if (player instanceof EntityPlayerMP) {
                NetworkConnectionPacket packet = new NetworkConnectionPacket(false);
                BuilderWandPacket builderWandPacket = new BuilderWandPacket(player.getPosition(), new HashMap<>());

                Network.sendTo(builderWandPacket, (EntityPlayerMP)player);
            }
        }

        return grid;
    }

    private IGrid findNearbyNetwork(World world, EntityPlayer player, long securityID) {
        // Check in a cube around the player for any ME components, not just wireless access points
        BlockPos playerPos = player.getPosition();
        WarrantXtra.logger.info("Searching for ME network components around " + playerPos);

        for (int x = -MAX_RANGE; x <= MAX_RANGE; x++) {
            for (int y = -MAX_RANGE; y <= MAX_RANGE; y++) {
                for (int z = -MAX_RANGE; z <= MAX_RANGE; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);

                    // Skip unloaded chunks
                    if (!world.isBlockLoaded(checkPos)) continue;

                    TileEntity te = world.getTileEntity(checkPos);
                    if (te == null) continue;

                    // Try all possible grid interfaces
                    IGrid grid = null;

                    // First try: IGridHost
                    if (te instanceof IGridHost) {
                        IGridHost host = (IGridHost) te;
                        for (AEPartLocation loc : AEPartLocation.values()) {
                            IGridNode node = host.getGridNode(loc);
                            if (node != null && node.getGrid() != null) {
                                grid = node.getGrid();
                                WarrantXtra.logger.info("Found grid through IGridHost at " + checkPos);
                                break;
                            }
                        }
                    }

                    // Second try: IActionHost
                    if (grid == null && te instanceof IActionHost) {
                        IActionHost host = (IActionHost) te;
                        IGridNode node = host.getActionableNode();
                        if (node != null && node.getGrid() != null) {
                            grid = node.getGrid();
                            WarrantXtra.logger.info("Found grid through IActionHost at " + checkPos);
                        }
                    }

                    // If we found a grid, check security and return it
                    if (grid != null) {
                        ISecurityGrid security = grid.getCache(ISecurityGrid.class);

                        // If no security or we have permission, use this grid
                        if (security == null || security.hasPermission(player, SecurityPermissions.BUILD)) {
                            WarrantXtra.logger.info("Found accessible ME network at " + checkPos);
                            return grid;
                        }
                    }
                }
            }
        }

        WarrantXtra.logger.warn("No accessible ME network components found within range");
        return null;
    }

    private boolean linkToNearbyMEComponent(World world, EntityPlayer player, ItemStack stack) {
        WarrantXtra.logger.info("Searching for any nearby ME network components");
        BlockPos playerPos = player.getPosition();

        // Use a smaller range for initial linking
        int linkRange = 8;

        for (int x = -linkRange; x <= linkRange; x++) {
            for (int y = -linkRange; y <= linkRange; y++) {
                for (int z = -linkRange; z <= linkRange; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);

                    // Skip unloaded chunks
                    if (!world.isBlockLoaded(checkPos)) continue;

                    TileEntity te = world.getTileEntity(checkPos);
                    if (te == null) continue;

                    // Check if this is any AE2 component
                    IGrid grid = null;

                    // Try IGridHost
                    if (te instanceof IGridHost) {
                        IGridHost host = (IGridHost) te;
                        for (AEPartLocation loc : AEPartLocation.values()) {
                            IGridNode node = host.getGridNode(loc);
                            if (node != null && node.getGrid() != null) {
                                grid = node.getGrid();
                                WarrantXtra.logger.info("Found grid through IGridHost at " + checkPos);
                                break;
                            }
                        }
                    }

                    // Try IActionHost
                    if (grid == null && te instanceof IActionHost) {
                        IActionHost host = (IActionHost) te;
                        IGridNode node = host.getActionableNode();
                        if (node != null && node.getGrid() != null) {
                            grid = node.getGrid();
                            WarrantXtra.logger.info("Found grid through IActionHost at " + checkPos);
                        }
                    }

                    if (grid != null) {
                        // Check if we have access
                        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
                        if (security == null || security.hasPermission(player, SecurityPermissions.BUILD)) {
                            // Store the link
                            if (!stack.hasTagCompound()) {
                                stack.setTagCompound(new NBTTagCompound());
                            }

                            NBTTagCompound tag = stack.getTagCompound();
                            assert tag != null;
                            tag.setBoolean(NBT_LINK_KEY, true);

                            // Store security ID if available
                            if (security != null) {
                                tag.setLong(NBT_SECURITY_KEY, security.getOwner());
                                WarrantXtra.logger.info("Linked to ME network with security ID: " + security.getOwner());
                            } else {
                                tag.setLong(NBT_SECURITY_KEY, player.getUniqueID().getLeastSignificantBits());
                                WarrantXtra.logger.info("Linked to ME network using player UUID");
                            }

                            return true;
                        }
                    }
                }
            }
        }

        WarrantXtra.logger.warn("No accessible ME network components found nearby");
        return false;
    }

    public int getWirelessSignalStrength(World world, EntityPlayer player, ItemStack stack) {
        if (!isLinked(stack)) {
            return 0;
        }

        IGrid grid = getNetworkFromStack(world, player, stack);
        if (grid == null) {
            return 0;
        }

        // Find closest access point
        BlockPos playerPos = new BlockPos(player);
        double bestSignal = 0;

        for (int x = -MAX_RANGE; x <= MAX_RANGE; x++) {
            for (int y = -MAX_RANGE; y <= MAX_RANGE; y++) {
                for (int z = -MAX_RANGE; z <= MAX_RANGE; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);
                    TileEntity te = world.getTileEntity(checkPos);

                    if (te instanceof IWirelessAccessPoint) {
                        IWirelessAccessPoint accessPoint = (IWirelessAccessPoint) te;
                        double distSq = player.getDistanceSq(checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5);
                        double range = accessPoint.getRange();

                        if (distSq <= range * range) {
                            // Calculate signal strength as percentage of max range
                            double signal = 100 * (1 - Math.sqrt(distSq) / range);
                            bestSignal = Math.max(bestSignal, signal);
                        }
                    }
                }
            }
        }

        return (int)bestSignal;
    }

    private boolean hasNetworkPermission(IGrid grid, EntityPlayer player) {
        if (grid == null) return false;

        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        if (security == null) return true; // No security = access granted

        return security.hasPermission(player, SecurityPermissions.BUILD);
    }

    // Add tooltip information
    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, World world, List<String> tooltip, net.minecraft.client.util.ITooltipFlag flag) {
        super.addInformation(stack, world, tooltip, flag);

        if (isLinked(stack)) {
            tooltip.add(TextFormatting.GREEN + "Linked to ME Network");

            EntityPlayer player = Minecraft.getMinecraft().player;
            if (player != null) {
                int signalStrength = getWirelessSignalStrength(world, player, stack);

                if (signalStrength > 0) {
                    tooltip.add(TextFormatting.AQUA + "Signal: " + signalStrength + "%");
                } else {
                    tooltip.add(TextFormatting.RED + "No signal");
                }
            }
        } else {
            tooltip.add(TextFormatting.RED + "Not linked to ME Network");
            tooltip.add(TextFormatting.GRAY + "Shift+Right-click to link");
        }

        tooltip.add("");
        tooltip.add(TextFormatting.GRAY + "Uses blocks from connected ME network");
        tooltip.add(TextFormatting.GRAY + "Right-click to place up to " + MAX_BLOCKS + " blocks");
    }

    // Display signal strength in-game (optional)
    @Override
    @SideOnly(Side.CLIENT)
    public boolean showDurabilityBar(ItemStack stack) {
        return isLinked(stack);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getDurabilityForDisplay(ItemStack stack) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player != null && isLinked(stack)) {
            int signalStrength = getWirelessSignalStrength(Minecraft.getMinecraft().world, player, stack);
            return 1.0 - (signalStrength / 100.0);
        }
        return 1.0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int getRGBDurabilityForDisplay(ItemStack stack) {
        return 0x00AAFF; // Blue color for signal strength
    }

    private void runBlockPlacementDiagnostics(World world, EntityPlayer player, List<BlockPos> positions, Block sourceBlock) {
        WarrantXtra.logger.info("==== ME Builder's Wand Diagnostics ====");
        WarrantXtra.logger.info("Player: " + player.getName() + " at " + player.getPosition());
        WarrantXtra.logger.info("Attempting to place: " + sourceBlock.getLocalizedName());
        WarrantXtra.logger.info("Number of target positions: " + positions.size());

        // Sample a few positions for detailed diagnostics
        int sampleSize = Math.min(positions.size(), 5);
        WarrantXtra.logger.info("Detailed analysis of " + sampleSize + " sample positions:");

        for (int i = 0; i < sampleSize; i++) {
            BlockPos pos = positions.get(i);
            Block existingBlock = world.getBlockState(pos).getBlock();

            WarrantXtra.logger.info("Position " + i + " - " + pos + ":");
            WarrantXtra.logger.info("  Current block: " + existingBlock.getLocalizedName());
            WarrantXtra.logger.info("  Is replaceable: " + existingBlock.isReplaceable(world, pos));
            WarrantXtra.logger.info("  Can place source block: " + sourceBlock.canPlaceBlockAt(world, pos));

            // Check for entities that might block placement
            List<Entity> entities = world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos));
            WarrantXtra.logger.info("  Entities at position: " + entities.size());

            // Check lighting level (some blocks might have requirements)
            WarrantXtra.logger.info("  Light level: " + world.getLight(pos));

            // Check for block update issues
            WarrantXtra.logger.info("  Adjacent solid faces: " +
                    countAdjacentSolidFaces(world, pos));
        }

        WarrantXtra.logger.info("====================================");
    }

    public void performDiagnosticCheck(World world, EntityPlayer player, ItemStack stack) {
        WarrantXtra.logger.info("======== ME BUILDER'S WAND DIAGNOSTIC CHECK ========");
        WarrantXtra.logger.info("Player: " + player.getName() + " at " + player.getPosition());
        WarrantXtra.logger.info("Dimension: " + world.provider.getDimension());

        // Check wand linking status
        boolean isLinked = isLinked(stack);
        WarrantXtra.logger.info("Wand linked: " + isLinked);

        if (isLinked && stack.hasTagCompound()) {
            NBTTagCompound tag = stack.getTagCompound();
            assert tag != null;
            WarrantXtra.logger.info("Security ID: " + (tag.hasKey(NBT_SECURITY_KEY) ? tag.getLong(NBT_SECURITY_KEY) : "None"));
        }

        // Check network connection
        IGrid grid = getNetworkFromStack(world, player, stack);
        WarrantXtra.logger.info("Network found: " + (grid != null));

        if (grid != null) {
            // Check security permissions
            ISecurityGrid security = grid.getCache(ISecurityGrid.class);
            boolean hasPermission = security == null || security.hasPermission(player, SecurityPermissions.BUILD);
            WarrantXtra.logger.info("Has build permission: " + hasPermission);

            // Check storage
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            WarrantXtra.logger.info("Storage grid available: " + (storageGrid != null));

            if (storageGrid != null) {
                IMEMonitor<IAEItemStack> storage = storageGrid.getInventory(
                        AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class));
                WarrantXtra.logger.info("Storage monitor available: " + (storage != null));
            }

            // Check other grid capabilities
            WarrantXtra.logger.info("Grid node count: " + grid.getNodes().size());
        }

        WarrantXtra.logger.info("===================================================");
    }

    // Helper method to count adjacent solid faces
    private int countAdjacentSolidFaces(World world, BlockPos pos) {
        int count = 0;
        for (EnumFacing facing : EnumFacing.values()) {
            BlockPos offsetPos = pos.offset(facing);
            if (world.getBlockState(offsetPos).isSideSolid(world, offsetPos, facing.getOpposite())) {
                count++;
            }
        }
        return count;
    }


    @SideOnly(Side.CLIENT)
    public static class RenderHandler {
        private BlockPos targetPos;
        private EnumFacing targetFace;
        private World world;
        private ItemMEBuildersWand wandItem;
        private Map<BlockPos, Boolean> availabilityCache = new HashMap<>();
        private long lastCheckTime = 0;
        private static final long CACHE_TIMEOUT = 500; // milliseconds
        private static final int COLOR_AVAILABLE = 0xAA00FFFF; // Cyan with alpha
        private static final int COLOR_UNAVAILABLE = 0xAAFF0000; // Red with alpha

        public RenderHandler() {
            MinecraftForge.EVENT_BUS.register(this);
        }

        public void updateRenderData(World world, BlockPos pos, EnumFacing face, ItemMEBuildersWand wand) {
            this.world = world;
            this.targetPos = pos;
            this.targetFace = face;
            this.wandItem = wand;

            // Reset cache periodically
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCheckTime > CACHE_TIMEOUT) {
                availabilityCache.clear();
                lastCheckTime = currentTime;
            }
        }

        @SubscribeEvent
        public void onRenderWorldLast(RenderWorldLastEvent event) {
            EntityPlayer player = Minecraft.getMinecraft().player;
            if (player == null || world == null || targetPos == null || targetFace == null ||
                    player.getHeldItemMainhand().getItem() != wandItem) {
                return;
            }

            // Check if the player is holding the wand
            ItemStack heldStack = player.getHeldItemMainhand();
            if (heldStack.isEmpty() || heldStack.getItem() != wandItem) {
                heldStack = player.getHeldItemOffhand();
                if (heldStack.isEmpty() || heldStack.getItem() != wandItem) {
                    return;
                }
            }

            // Get the ray trace result to find what block the player is looking at
            RayTraceResult rayTraceResult = Minecraft.getMinecraft().objectMouseOver;
            if (rayTraceResult == null || rayTraceResult.typeOfHit != RayTraceResult.Type.BLOCK) {
                return;
            }

            // Update positions based on the block the player is looking at
            BlockPos lookingAt = rayTraceResult.getBlockPos();
            EnumFacing face = rayTraceResult.sideHit;

            // Calculate the positions that would be filled
            List<BlockPos> positions = wandItem.calculateBuildArea(world, targetPos, targetFace, MAX_BLOCKS);
//            List<BlockPos> positions = wandItem.calculateBuildArea(world, lookingAt, face, MAX_BLOCKS);
            if (positions.isEmpty()) {
                return;
            }

            boolean isLinked = wandItem.isLinked(heldStack);

            // Check block availability in ME system (client-side approximation)
            Block sourceBlock = world.getBlockState(targetPos).getBlock();
            checkBlockAvailability(positions, sourceBlock);

            // Render
            renderOutlines(player, event.getPartialTicks(), positions, isLinked);
        }

        private void checkBlockAvailability(List<BlockPos> positions, Block sourceBlock) {
            // This is a client-side approximation - would need server sync for accuracy
            // but that would create too much network traffic
            // For now, we'll use the cache and assume blocks are available

            for (BlockPos pos : positions) {
                if (!availabilityCache.containsKey(pos)) {
                    // Default to true for responsive UI - server will validate on placement
                    availabilityCache.put(pos, true);
                }
            }
        }

        private void renderOutlines(EntityPlayer player, float partialTicks, List<BlockPos> positions, boolean isLinked) {
            double doubleX = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
            double doubleY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
            double doubleZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;

            GlStateManager.pushMatrix();
            GlStateManager.translate(-doubleX, -doubleY, -doubleZ);
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.disableLighting();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO);

            for (BlockPos pos : positions) {
                // If not linked, show everything as unavailable
                if (!isLinked) {
                    drawSelectionBox(pos, COLOR_UNAVAILABLE);
                    continue;
                }
                boolean isAvailable = availabilityCache.getOrDefault(pos, false);
                drawSelectionBox(pos, isAvailable ? COLOR_AVAILABLE : COLOR_UNAVAILABLE);
            }

            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        }

        private void drawSelectionBox(BlockPos pos, int colour) {
            float red = ((colour >> 16) & 0xFF) / 255.0F;
            float green = ((colour >> 8) & 0xFF) / 255.0F;
            float blue = (colour & 0xFF) / 255.0F;
            float alpha = ((colour >> 24) & 0xFF) / 255.0F;

            AxisAlignedBB box = new AxisAlignedBB(pos).grow(0.002);

            // Draw transparent filled box
            RenderGlobal.renderFilledBox(box, red, green, blue, alpha * 0.3F);

            // Draw outline
            RenderGlobal.drawSelectionBoundingBox(box, red, green, blue, alpha * 0.8F);
        }

        // Method to update availability cache with real data from server
        public void updateAvailabilityCache(Map<BlockPos, Boolean> newData) {
            availabilityCache.putAll(newData);
            lastCheckTime = System.currentTimeMillis();
        }
    }

    @SideOnly(Side.CLIENT)
    public static void initClient() {
        renderHandler = new RenderHandler();
    }
}
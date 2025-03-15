package uk.zatcham.warrantxtra;

import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.IGrid;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class WirelessNetworkHandler {
    private static final int NETWORK_CHECK_INTERVAL = 20; // ticks
    private int checkCounter = 0;
    private IGrid cachedGrid = null;
    private long lastCheckTime = 0;

    /**
     * Tries to find and connect to a wireless network
     * @param world The world
     * @param player The player
     * @param securityId The security ID to use
     * @return The connected grid or null if none found
     */
    public IGrid findAndConnectToNetwork(World world, EntityPlayer player, long securityId) {
        long currentTime = System.currentTimeMillis();

        // Use cached grid if valid and within time limit
        if (cachedGrid != null && currentTime - lastCheckTime < 5000) {
            return cachedGrid;
        }

        // Search for a network
        IGrid foundGrid = searchForNetwork(world, player, securityId);

        // Update cache
        if (foundGrid != null) {
            cachedGrid = foundGrid;
            lastCheckTime = currentTime;
        } else {
            cachedGrid = null;
        }

        return cachedGrid;
    }

    /**
     * Searches for a wireless network
     */
    private IGrid searchForNetwork(World world, EntityPlayer player, long securityId) {
        WarrantXtra.logger.info("Searching for wireless network for player: " + player.getName());

        // Try to find a security key-enabled network first
        IGrid securityNetwork = findNetworkWithSecurityId(world, player, securityId);
        if (securityNetwork != null) {
            return securityNetwork;
        }

        // If no security network, try to find any accessible network
        return findAnyAccessibleNetwork(world, player);
    }

    /**
     * Finds a network with a specific security ID
     */
    private IGrid findNetworkWithSecurityId(World world, EntityPlayer player, long securityId) {
        BlockPos playerPos = new BlockPos(player);
        int maxRange = ItemMEBuildersWand.MAX_RANGE;

        for (int x = -maxRange; x <= maxRange; x++) {
            for (int y = -maxRange; y <= maxRange; y++) {
                for (int z = -maxRange; z <= maxRange; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);
                    if (!world.isBlockLoaded(checkPos)) continue;

                    TileEntity te = world.getTileEntity(checkPos);
                    if (te instanceof IWirelessAccessPoint) {
                        IWirelessAccessPoint accessPoint = (IWirelessAccessPoint) te;
                        double distSq = player.getDistanceSq(checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5);
                        double range = accessPoint.getRange();

                        if (distSq <= range * range) {
                            IGridNode node = accessPoint.getActionableNode();
                            if (node != null && node.getGrid() != null) {
                                IGrid grid = node.getGrid();
                                ISecurityGrid security = grid.getCache(ISecurityGrid.class);

                                if (security != null && security.getOwner() == securityId) {
                                    WarrantXtra.logger.info("Found security-matched network at " + checkPos);
                                    return grid;
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Finds any accessible network
     */
    private IGrid findAnyAccessibleNetwork(World world, EntityPlayer player) {
        BlockPos playerPos = new BlockPos(player);
        int maxRange = ItemMEBuildersWand.MAX_RANGE;

        for (int x = -maxRange; x <= maxRange; x++) {
            for (int y = -maxRange; y <= maxRange; y++) {
                for (int z = -maxRange; z <= maxRange; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);
                    if (!world.isBlockLoaded(checkPos)) continue;

                    TileEntity te = world.getTileEntity(checkPos);
                    if (te instanceof IWirelessAccessPoint) {
                        IWirelessAccessPoint accessPoint = (IWirelessAccessPoint) te;
                        double distSq = player.getDistanceSq(checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5);
                        double range = accessPoint.getRange();

                        if (distSq <= range * range) {
                            IGridNode node = accessPoint.getActionableNode();
                            if (node != null && node.getGrid() != null) {
                                IGrid grid = node.getGrid();
                                ISecurityGrid security = grid.getCache(ISecurityGrid.class);

                                if (security == null || security.hasPermission(player, SecurityPermissions.BUILD)) {
                                    WarrantXtra.logger.info("Found accessible network at " + checkPos);
                                    return grid;
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Checks if the player has permission to use the network
     */
    public boolean hasPermission(IGrid grid, EntityPlayer player, SecurityPermissions permission) {
        if (grid == null) return false;

        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        if (security == null) return true; // No security = full access

        return security.hasPermission(player, permission);
    }

    /**
     * Invalidates the cached grid
     */
    public void invalidateCache() {
        cachedGrid = null;
    }
}

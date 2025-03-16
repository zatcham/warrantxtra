package uk.zatcham.warrantxtra.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import uk.zatcham.warrantxtra.WarrantXtra;

public class Network {
    private static SimpleNetworkWrapper INSTANCE;
    private static int id = 0;

    public static void init() {
        INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(WarrantXtra.MODID);
        if (INSTANCE != null) {
            WarrantXtra.logger.info("Network instance created successfully.");
        } else {
            WarrantXtra.logger.error("Failed to create network instance.");
        }
//        // discriminator is not used in this case, but it's a required parameter
        INSTANCE.registerMessage(BuilderWandPacket.Handler.class, BuilderWandPacket.class, id++, Side.CLIENT);
        INSTANCE.registerMessage(BuilderWandPacket.Handler.class, BuilderWandPacket.class, id++, Side.SERVER);
    }

    public static void sendToServer(BuilderWandPacket packet) {
        if (INSTANCE != null) {
            INSTANCE.sendToServer(packet);
        } else {
            WarrantXtra.logger.error("Network instance is null. Packet not sent to server.");
        }
    }

    public static void sendTo(BuilderWandPacket packet, EntityPlayerMP player) {
        if (INSTANCE != null) {
            INSTANCE.sendTo(packet, player);
        } else {
            WarrantXtra.logger.error("Network instance is null. Packet not sent to player.");
        }
    }

    public static SimpleNetworkWrapper getInstance() {
        return INSTANCE;
    }
}
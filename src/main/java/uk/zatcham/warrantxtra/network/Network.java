package uk.zatcham.warrantxtra.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class Network {
    private static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel("warrantxtra");
    private static int id = 0;

    public static void init() {
        INSTANCE.registerMessage(BuilderWandPacket.Handler.class, BuilderWandPacket.class, id++, Side.CLIENT);
        INSTANCE.registerMessage(BuilderWandPacket.Handler.class, BuilderWandPacket.class, id++, Side.SERVER);
    }

    public static void sendToServer(BuilderWandPacket packet) {
        INSTANCE.sendToServer(packet);
    }

    public static void sendTo(BuilderWandPacket packet, EntityPlayerMP player) {
        INSTANCE.sendTo(packet, player);
    }
}
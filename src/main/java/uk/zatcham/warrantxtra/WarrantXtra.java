package uk.zatcham.warrantxtra;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.Logger;
import uk.zatcham.warrantxtra.items.ItemMEBuildersWand;
import uk.zatcham.warrantxtra.network.Network;

import java.util.Objects;

// WarrantXtra.java
@Mod(modid = WarrantXtra.MODID, name = WarrantXtra.NAME, version = WarrantXtra.VERSION)
//@Mod.EventBusSubscriber
public class WarrantXtra {
    public static final String MODID = "warrantxtra";
    public static final String NAME = "Zach's Warrant Xtras";
    public static final String VERSION = "1.0";

    public static Logger logger;

    // Then create the item
    public static final ItemMEBuildersWand ME_BUILDERS_WAND = new ItemMEBuildersWand();

    // Create the creative tab first
    public static final CreativeTabs WARRANT_XTRA_TAB = new CreativeTabs("warrantxtra") {
        @Override
        @SideOnly(Side.CLIENT)
        public ItemStack getTabIconItem() {
            return new ItemStack(ME_BUILDERS_WAND);
        }
    };

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        Network.init();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        WarrantXtra.logger.info("Kicking off WarrantXtra initialisation");
        if (event.getSide().isClient()) {
            WarrantXtra.logger.info("Initialising client-side components");
            ItemMEBuildersWand.initClient();
        }
        logger.info("WarrantXtra initialised!");
    }

    @Mod.EventBusSubscriber
    public static class RegistrationHandler {
        @SubscribeEvent
        public static void registerItems(RegistryEvent.Register<Item> event) {
            event.getRegistry().register(ME_BUILDERS_WAND);
            logger.info("Registered item: " + ME_BUILDERS_WAND.getRegistryName());
        }
    }

    @Mod.EventBusSubscriber(Side.CLIENT)
    public static class ClientRegistrationHandler {
        @SubscribeEvent
        public static void registerModels(ModelRegistryEvent event) {
            ModelLoader.setCustomModelResourceLocation(
                    ME_BUILDERS_WAND,
                    0,
                    new ModelResourceLocation(Objects.requireNonNull(ME_BUILDERS_WAND.getRegistryName()), "inventory")
            );
        }
    }


}

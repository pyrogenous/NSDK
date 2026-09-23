package cc.nitea.example;

import cc.nitea.Nitea;
import cc.nitea.NiteaClient;
import cc.nitea.NiteaOptions;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * Test mod for the Nitea library. It has a block, a food item and a "faulty wand" that fails on purpose, records
 * what the mod does as breadcrumbs ({@link ActionTracker}), and adds {@code /examplemod} (or {@code /em}) subcommands
 * to send player reports and trigger test errors and crashes ({@link ModCommands}).
 */
@Mod(NiteaExample.MODID)
public final class NiteaExample {
    public static final String MODID = "niteaexample";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Set in the constructor, before anything else in the mod runs. */
    public static NiteaClient NITEA;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", p -> p.mapColor(MapColor.STONE));
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", p -> p.food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));
    public static final DeferredItem<FaultyWandItem> FAULTY_WAND = ITEMS.registerItem("faulty_wand", FaultyWandItem::new, p -> p.stacksTo(1));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_MODE_TABS.register("nitea_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.niteaexample"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> FAULTY_WAND.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(FAULTY_WAND.get());
                output.accept(EXAMPLE_ITEM.get());
                output.accept(EXAMPLE_BLOCK_ITEM.get());
            }).build());

    public NiteaExample(IEventBus modEventBus, ModContainer modContainer) {
        // Start Nitea first, so problems in the rest of the mod's startup are reported too.
        // The SDK key comes from the git-ignored .env file (bundled at build time), never from source code.
        NITEA = Nitea.init(NiteaOptions.builder(MODID)
                .owner(NiteaExample.class)
                .release(modContainer.getModInfo().getVersion().toString())
                .gameDir(FMLPaths.GAMEDIR.get())
                .debug(Boolean.getBoolean("nitea.debug"))
                .build());
        NITEA.addBreadcrumb("lifecycle", "Mod constructed");

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);

        NeoForge.EVENT_BUS.register(ActionTracker.class);
        NeoForge.EVENT_BUS.addListener(ModCommands::register);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        NITEA.addBreadcrumb("lifecycle", "Common setup done");
        LOGGER.info("Nitea reporting is {}", NITEA.isEnabled() ? "on" : "off");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
    }
}

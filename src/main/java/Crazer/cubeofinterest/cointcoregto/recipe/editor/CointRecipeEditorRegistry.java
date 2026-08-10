package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class CointRecipeEditorRegistry {
    public static final String ITEM_ID = "recipe_editor";
    public static final String MENU_ID = "recipe_editor_menu";
    public static final String CRAFTING_MENU_ID = "crafting_recipe_editor_menu";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, CointCoreGTO.MODID);

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CointCoreGTO.MODID);

    public static final RegistryObject<Item> RECIPE_EDITOR = ITEMS.register(
            ITEM_ID,
            () -> new RecipeEditorItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<MenuType<RecipeEditorMenu>> RECIPE_EDITOR_MENU = MENUS.register(
            MENU_ID,
            () -> IForgeMenuType.create((containerId, inventory, data) -> {
                String initialType = RecipeEditorMenu.DEFAULT_RECIPE_TYPE;
                if (data != null && data.readableBytes() > 0) {
                    initialType = data.readUtf(160);
                }
                return new RecipeEditorMenu(containerId, inventory, initialType);
            })
    );

    public static final RegistryObject<MenuType<CraftingRecipeEditorMenu>> CRAFTING_RECIPE_EDITOR_MENU = MENUS.register(
            CRAFTING_MENU_ID,
            () -> IForgeMenuType.create((containerId, inventory, data) ->
                    new CraftingRecipeEditorMenu(containerId, inventory))
    );

    private CointRecipeEditorRegistry() {
    }

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
        MENUS.register(eventBus);
        eventBus.addListener(CointRecipeEditorRegistry::addToCreativeTab);
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RECIPE_EDITOR.get());
        }
    }
}
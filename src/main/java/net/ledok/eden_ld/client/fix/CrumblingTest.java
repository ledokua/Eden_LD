package net.ledok.eden_ld.client.fix;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.loader.api.FabricLoader;
import net.ledok.eden_ld.EdenLD;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Dev only ({@code EDEN_CRUMBLE_TEST=1} in a development environment, needs Extended Drawers and Sodium in the run's
 * mods): a quad drawer with four items right in front of the player, mined (client-side breaking progress) while
 * {@code CrumblingTestTriggerMixin} ends the crumbling buffer before every item render, the way the live crash's
 * unknown culprit does. {@code EDEN_CRUMBLE_FIX=0} turns {@code fresh_crumbling_overlay} off to see the crash;
 * {@code EDEN_CRUMBLE_TRIGGER=0} leaves the buffer alone (to look at the overlay, screenshot at tick 120).
 */
public final class CrumblingTest {

    public static final boolean ENABLED = FabricLoader.getInstance().isDevelopmentEnvironment()
            && "1".equals(System.getenv("EDEN_CRUMBLE_TEST"));

    private static int tick;
    private static BlockPos drawerPos;
    private static boolean done;

    private CrumblingTest() {
    }

    /** Only while the drawer is being mined in this test. */
    public static boolean endCrumblingBufferNow() {
        return drawerPos != null && tick > 60 && tick < 200 && !"0".equals(System.getenv("EDEN_CRUMBLE_TRIGGER"));
    }

    public static void registerIfRequested() {
        if (!ENABLED) {
            return;
        }
        if ("0".equals(System.getenv("EDEN_CRUMBLE_FIX"))) {
            EdenLD.CONFIG.client_fixes.fresh_crumbling_overlay = false;
        }
        ClientTickEvents.END_CLIENT_TICK.register(CrumblingTest::tick);
    }

    private static void tick(Minecraft mc) {
        if (done || mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
            return;
        }
        mc.options.pauseOnLostFocus = false;
        tick++;
        if (tick == 30) {
            setup(mc);
        }
        if (drawerPos != null && tick > 60 && tick < 200) {
            mc.level.destroyBlockProgress(mc.player.getId(), drawerPos, (tick / 4) % 10);
        }
        if (tick == 120) {
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "eden-crumble-test.png", mc.getMainRenderTarget(), message -> {
            });
        }
        if (tick == 200) {
            mc.level.destroyBlockProgress(mc.player.getId(), drawerPos, -1);
            EdenLD.LOGGER.info("[CrumbleTest] mined the drawer for 140 ticks without crashing (fresh_crumbling_overlay={})",
                    EdenLD.CONFIG.client_fixes.fresh_crumbling_overlay);
            done = true;
            mc.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static void setup(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        BlockPos player = mc.player.blockPosition();
        drawerPos = player.relative(Direction.NORTH, 2);
        server.execute(() -> {
            ServerLevel level = server.getLevel(mc.level.dimension());
            var serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
            serverPlayer.setGameMode(GameType.CREATIVE);
            BlockState drawer = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("extended_drawers:quad_drawer")).defaultBlockState();
            for (Property<?> property : drawer.getProperties()) {
                if (property.getName().equals("facing") && property.getPossibleValues().contains(Direction.SOUTH)) {
                    drawer = drawer.setValue((Property<Direction>) property, Direction.SOUTH);
                }
            }
            level.removeBlock(drawerPos, false);
            level.setBlockAndUpdate(drawerPos, drawer);
            ItemStack[] items = {new ItemStack(Items.CHEST, 8), new ItemStack(Items.DIAMOND, 32),
                    new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.STONE, 32)};
            var storage = ItemStorage.SIDED.find(level, drawerPos, Direction.SOUTH);
            if (storage != null) {
                try (Transaction tx = Transaction.openOuter()) {
                    int slot = 0;
                    for (var view : storage) {
                        if (slot < items.length && view instanceof SingleSlotStorage<ItemVariant> single) {
                            single.insert(ItemVariant.of(items[slot]), items[slot].getCount(), tx);
                            slot++;
                        }
                    }
                    tx.commit();
                }
            }
            serverPlayer.teleportTo(level, drawerPos.getX() + 0.5, drawerPos.getY(), drawerPos.getZ() + 2.0, 180, 25);
        });
    }
}

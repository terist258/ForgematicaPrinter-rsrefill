package me.aleksilassila.litematica.printer.rs;

import net.minecraft.client.MinecraftClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod.EventBusSubscriber(modid = "forgematica_printer", value = Dist.CLIENT)
public class RSRefillTickListener {

    private static final Logger LOGGER = LogManager.getLogger("RSRefiller");

    /** 等待 RS 界面出现的 tick 计数 */
    private static int waitTicks = 0;
    private static final int MAX_WAIT = 40;

    /** 界面出现后，先等 view 同步（RS 分批同步物品），避免读到未同步的 view 误判"网络里没有" */
    private static int viewSyncTicks = 0;
    private static final int VIEW_SYNC = 3;

    /** 取物包发出后，等待物品真正进背包的 tick（到账即恢复，不必死等） */
    private static int arriveTicks = 0;
    private static final int MAX_ARRIVE = 20;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        // 等待 RS 界面出现并取物
        if (RSRefiller.isBusy() && ScreenInterceptor.isArmed()) {
            waitTicks++;

            if (ScreenInterceptor.isRSGridScreen(client.currentScreen)) {
                // 界面刚打开，先等 view 同步，避免读到未同步的 view 误判"网络里没有"
                viewSyncTicks++;
                if (viewSyncTicks < VIEW_SYNC) {
                    return;
                }

                if (RSRefiller.tryPull(client)) {
                    LOGGER.info("[RSRefiller] pull sent, waiting for item to arrive");
                    ScreenInterceptor.disarm();
                    arriveTicks = MAX_ARRIVE;   // 轮询到账，不再固定等 2 tick
                } else if (RSRefiller.isNoMatch()) {
                    // view 已同步（等过 3 tick），此时判 absent 才可信
                    LOGGER.info("[RSRefiller] item genuinely absent in network (view synced), closing");
                    ScreenInterceptor.disarm();
                    RSRefiller.closeContainer();
                    RSRefiller.resetBusy();
                    waitTicks = 0;
                    viewSyncTicks = 0;
                } else if (waitTicks >= MAX_WAIT) {
                    LOGGER.info("[RSRefiller] timeout waiting for pull");
                    ScreenInterceptor.disarm();
                    RSRefiller.closeContainer();
                    RSRefiller.resetBusy();
                    waitTicks = 0;
                    viewSyncTicks = 0;
                }
            } else if (waitTicks >= MAX_WAIT) {
                LOGGER.info("[RSRefiller] timeout: RS grid screen never appeared");
                ScreenInterceptor.disarm();
                RSRefiller.resetBusy();
                waitTicks = 0;
                viewSyncTicks = 0;
            }
        } else {
            waitTicks = 0;
            viewSyncTicks = 0;
        }

        // 等待物品到账：到账立刻关界面并恢复打印（最快恢复，且不会重复取）
        if (arriveTicks > 0) {
            arriveTicks--;
            if (RSRefiller.hasArrived(client.player)) {
                RSRefiller.closeContainer();
                RSRefiller.resetBusy();
                arriveTicks = 0;
            } else if (arriveTicks == 0) {
                RSRefiller.markNotArrived();
                RSRefiller.closeContainer();
                RSRefiller.resetBusy();
                arriveTicks = 0;
            }
        }
    }
}
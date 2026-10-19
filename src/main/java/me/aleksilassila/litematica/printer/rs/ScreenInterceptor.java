package me.aleksilassila.litematica.printer.rs;

import net.minecraft.client.gui.screen.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod.EventBusSubscriber(modid = "forgematica_printer", value = Dist.CLIENT)
public class ScreenInterceptor {

    private static final Logger LOGGER = LogManager.getLogger("RSRefiller");

    private static volatile boolean armed = false;

    /** 开启拦截：等下一个 RS 网格界面出现 */
    public static void arm() {
        armed = true;
        LOGGER.info("[RSRefiller] ScreenInterceptor: armed (waiting for RS grid screen)");
    }

    public static void disarm() {
        armed = false;
    }

    public static boolean isArmed() {
        return armed;
    }

    /** 判断是否是 RS 网格界面（按类名，无编译期依赖） */
    public static boolean isRSGridScreen(Screen screen) {
        if (screen == null) return false;
        String cn = screen.getClass().getName().toLowerCase();
        return cn.contains("refinedstorage") && cn.contains("grid");
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!armed) return;
        Screen screen = event.getScreen();
        if (isRSGridScreen(screen)) {
            LOGGER.info("[RSRefiller] ScreenInterceptor: RS grid screen opening: {}",
                    screen.getClass().getName());
        }
    }
}
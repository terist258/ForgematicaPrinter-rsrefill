package me.aleksilassila.litematica.printer;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.actions.Action;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.config.Hotkeys;
import me.aleksilassila.litematica.printer.guides.Guide;
import me.aleksilassila.litematica.printer.guides.Guides;
import me.aleksilassila.litematica.printer.rs.RSRefiller;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.Inventory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class Printer {
    public static final Logger logger = LogManager.getLogger(PrinterReference.MOD_ID);
    @Nonnull
    public final ClientPlayerEntity player;
    public final ActionHandler actionHandler;
    private final Guides interactionGuides = new Guides();

    public Printer(@Nonnull MinecraftClient client, @Nonnull ClientPlayerEntity player) {
        this.player = player;
        this.actionHandler = new ActionHandler(client, player);
    }

    public boolean onGameTick() {
        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();

        if (!actionHandler.acceptsActions()) {
            return false;
        }

        if (worldSchematic == null) {
            return false;
        }

        if (!Configs.PRINT_MODE.getBooleanValue() && !Hotkeys.PRINT.getKeybind().isPressed()) {
            return false;
        }

        PlayerAbilities abilities = player.getAbilities();
        if (!abilities.allowModifyWorld) {
            return false;
        }

        // RS 取物 / 界面打开期间不放置：
        // 界面开着时切换快捷栏会失败，可能把方块放成手上的东西
        if (RSRefiller.isBusy()) {
            return false;
        }

        List<BlockPos> positions = getReachablePositions();
        findBlock:
        for (BlockPos position : positions) {
            SchematicBlockState state = new SchematicBlockState(player.getWorld(), worldSchematic, position);
            if (state.targetState.equals(state.currentState) || state.targetState.isAir()) {
                continue;
            }

            ItemStack neededStack = new ItemStack(state.targetState.getBlock().asItem());
            boolean hasItem = hasItemInInventory(player, neededStack);
            boolean isBusy = RSRefiller.isBusy();

            // 流体（水/岩浆）没有对应物品，无法打印：直接跳过，避免空转触发反复取物
            if (neededStack.isEmpty()) {
                continue;
            }
            // 之前这里没有 continue，流体位置每 tick 都进 guides 空转，
            // 让打印机一直"有事要做"，从而反复触发附近方块取物、反复开关存储网络。
            if (neededStack.isEmpty()) {
                if (!state.targetState.getFluidState().isEmpty()) {
                    logger.info("[RSRefiller] SKIP fluid block: {} at {}", state.targetState.getBlock(), position);
                } else {
                    logger.info("[RSRefiller] SKIP no-item block: {} at {}", state.targetState.getBlock(), position);
                }
                continue;
            }

            if (!neededStack.isEmpty()) {
                logger.info("[RSRefiller] block={}, needed={}, hasItem={}, isBusy={}",
                        state.targetState.getBlock(), neededStack.getItem(), hasItem, isBusy);
            }

            if (!neededStack.isEmpty() && !hasItem) {
                if (RSRefiller.tryRefill(neededStack)) {
                    logger.info("[RSRefiller] requesting {} from storage network", neededStack.getName());
                    return false;
                } else if (RSRefiller.isCoolingDown(neededStack.getItem())) {
                    continue findBlock;
                } else {
                    logger.info("[RSRefiller] tryRefill returned false");
                }
            }

            Guide[] guides = interactionGuides.getInteractionGuides(state);

            BlockHitResult result = RayTraceUtils.traceToSchematicWorld(player, 10, true, true);
            boolean isCurrentlyLookingSchematic = result != null && result.getBlockPos().equals(position);

            for (Guide guide : guides) {
                // Add INTERACT_BLOCKS pull by DarkReaper231
                if (guide.canExecute(player) && Configs.INTERACT_BLOCKS.getBooleanValue()) {
                    printDebug("Executing {} for {}", guide, state);
                    List<Action> actions = guide.execute(player);
                    actionHandler.addActions(actions.toArray(Action[]::new));
                    return true;
                }
                if (guide.skipOtherGuides()) {
                    continue findBlock;
                }
            }
        }

        return false;
    }

    private List<BlockPos> getReachablePositions() {
        int maxReach = (int) Math.ceil(Configs.PRINTING_RANGE.getDoubleValue());
        double maxReachSquared = MathHelper.square(Configs.PRINTING_RANGE.getDoubleValue());

        ArrayList<BlockPos> positions = new ArrayList<>();

        for (int y = -maxReach; y < maxReach + 1; y++) {
            for (int x = -maxReach; x < maxReach + 1; x++) {
                for (int z = -maxReach; z < maxReach + 1; z++) {
                    BlockPos blockPos = player.getBlockPos().north(x).west(z).up(y);

                    if (!DataManager.getRenderLayerRange().isPositionWithinRange(blockPos)) {
                        continue;
                    }
                    if (this.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(blockPos)) > maxReachSquared) {
                        continue;
                    }

                    positions.add(blockPos);
                }
            }
        }

        return positions.stream()
                .filter(p ->
                {
                    Vec3d vec = Vec3d.ofCenter(p);
                    return this.player.getPos().squaredDistanceTo(vec) > 1
                            && this.player.getEyePos().squaredDistanceTo(vec) > 1;
                })
                .sorted((a, b) ->
                {
                    double aDistance = this.player.getPos().squaredDistanceTo(Vec3d.ofCenter(a));
                    double bDistance = this.player.getPos().squaredDistanceTo(Vec3d.ofCenter(b));
                    return Double.compare(aDistance, bDistance);
                }).toList();
    }

    private boolean hasItemInInventory(ClientPlayerEntity player, ItemStack stack) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack slot = inv.getStack(i);
            if (!slot.isEmpty() && slot.getItem() == stack.getItem()) {
                return true;
            }
        }
        return false;
    }

    public static void printDebug(String key, Object... args) {
        if (Configs.PRINT_DEBUG.getBooleanValue()) {
            logger.info(key, args);
        }
    }
}
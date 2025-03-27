package com.simpletombstone;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
import net.minecraft.block.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.item.ItemStack;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import java.util.*;

public class SimpleTombstone implements ModInitializer {
    public static final String MOD_ID = "simple-tombstone";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<BlockPos, PlayerTombstoneData> TOMBSTONE_CHESTS = new HashMap<>();
    private static final Set<UUID> DEAD_PLAYERS = new HashSet<>();
    private static final Set<UUID> RESURRECTED_PLAYERS = new HashSet<>();

    @Override
    public void onInitialize() {
        if (
                true
                //FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER
        ) {
            LOGGER.info("[SimpleTombstone] 服务器端初始化中...");

            // 监听玩家死亡事件
            ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
                if (entity instanceof ServerPlayerEntity player) {
                    LOGGER.info("[SimpleTombstone] 检测到玩家 {} 死亡，创建墓碑...", player.getName().getString());
                    createTombstoneForMixin(player);
                    DEAD_PLAYERS.add(player.getUuid());
                    return false; // 阻止默认死亡逻辑，防止玩家直接死亡
                }
                return true;
            });

            // 监听玩家重生事件
            ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
                UUID playerId = newPlayer.getUuid();
                if (DEAD_PLAYERS.contains(playerId)) {
                    LOGGER.info("[SimpleTombstone] 玩家 {} 已重生，物品归还功能已启用。", newPlayer.getName().getString());
                    DEAD_PLAYERS.remove(playerId);
                    RESURRECTED_PLAYERS.add(playerId);
                }
            });

            // 监听服务器每个 tick 检查玩家是否靠近墓碑
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (RESURRECTED_PLAYERS.contains(player.getUuid())) {
                        checkPlayerNearTombstone(player);
                    }
                }
            });

            // 注册花盆右键事件监听
            UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
                if (world.isClient()) {
                    return ActionResult.PASS; // 只在服务器端处理
                }

                BlockPos pos = hitResult.getBlockPos();
                BlockState state = world.getBlockState(pos);

                // 检查玩家是否点击了花盆
                if (state.getBlock() == Blocks.FLOWER_POT) {
                    TombstoneStorage storage = TombstoneStorage.load((ServerWorld) world);
                    // 检查是否有墓碑数据
                    for (Map.Entry<BlockPos, PlayerTombstoneData> entry : storage.getTombstoneData().entrySet()) {
                        // 如果点击的花盆与墓碑位置相匹配
                        if (entry.getKey().equals(pos)) {
                            PlayerTombstoneData data = entry.getValue();
                            // 将物品恢复给玩家
                            for (ItemStack stack : data.items()) {
                                player.getInventory().offerOrDrop(stack);
                            }

                            // 删除墓碑和数据
                            world.removeBlock(pos, false);
                            storage.removeTombstone(pos);
                            player.sendMessage(Text.of("你的物品已经从墓碑中恢复！"), false);
                            LOGGER.info("[SimpleTombstone] 玩家 {} 恢复了物品并删除了墓碑。", player.getName().getString());
                            break;
                        }
                    }
                }
                return ActionResult.PASS;
            });



            LOGGER.info("[SimpleTombstone] 服务器端初始化完成");
        }
    }

    public static void createTombstoneForMixin(ServerPlayerEntity player) {
        BlockPos deathPos = player.getBlockPos();
        World world = player.getWorld();

        while (world.isAir(deathPos.down())) {
            deathPos = deathPos.down();
        }
        deathPos = deathPos.down();

        // 只有下方是液体时才生成玻璃
        if (world.getBlockState(deathPos.down()).getBlock() instanceof FluidBlock) {
            LOGGER.warn("[SimpleTombstone] 位置 {} 处下方为液体，替换为玻璃。", deathPos.toShortString());
            world.setBlockState(deathPos, Blocks.GLASS.getDefaultState());
        } else if (!world.getBlockState(deathPos).isFullCube(world, deathPos)) {
            LOGGER.warn("[SimpleTombstone] 位置 {} 处非实体块，替换为玻璃。", deathPos.toShortString());
            world.setBlockState(deathPos, Blocks.GLASS.getDefaultState());
        }

        BlockPos tombstonePos = deathPos.up();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().removeStack(i, Integer.MAX_VALUE);
            if (!stack.isEmpty()) {
                items.add(stack);
            }
        }
        PlayerTombstoneData tombstoneData = new PlayerTombstoneData(player.getUuid(), items);
        TOMBSTONE_CHESTS.put(tombstonePos, tombstoneData);

        // Ensure TombstoneStorage is loaded and Tombstone is added immediately
        TombstoneStorage storage = TombstoneStorage.load((ServerWorld) world);
        storage.addTombstone(tombstonePos, tombstoneData);  // Write tombstone data immediately

        Random random = new Random();
        List<Block> flowerPots = Arrays.asList(
                Blocks.POTTED_OAK_SAPLING,
                Blocks.POTTED_DANDELION,
                Blocks.POTTED_POPPY,
                Blocks.POTTED_BLUE_ORCHID
        );
        Block chosenFlowerPot = flowerPots.get(random.nextInt(flowerPots.size()));
        world.setBlockState(tombstonePos, chosenFlowerPot.getDefaultState());

        player.sendMessage(Text.of("A loot chest has been placed at " + deathPos.toShortString()), false);
        LOGGER.info("[SimpleTombstone] 为玩家 {} 在 {} 创建了墓碑。", player.getName().getString(), deathPos.toShortString());
    }

    private void checkPlayerNearTombstone(ServerPlayerEntity player) {
        World world = player.getWorld();
        BlockPos playerPos = player.getBlockPos();
        TombstoneStorage storage = TombstoneStorage.load((ServerWorld) world);

        for (BlockPos pos : BlockPos.iterate(
                playerPos.getX() - 4, playerPos.getY() - 4, playerPos.getZ() - 4,
                playerPos.getX() + 4, playerPos.getY() + 4, playerPos.getZ() + 4
        )) {
            PlayerTombstoneData data = TOMBSTONE_CHESTS.get(pos);
            if (data != null && data.playerId().equals(player.getUuid())) {
                Block block = world.getBlockState(pos).getBlock();
                if (block instanceof FlowerPotBlock) {
                    LOGGER.info("[SimpleTombstone] 玩家 {} 靠近墓碑，恢复物品。", player.getName().getString());
                    for (ItemStack stack : data.items()) {
                        player.getInventory().offerOrDrop(stack);
                    }
                    if (world.getBlockState(pos.down()).getBlock() == Blocks.GLASS) {
                        world.removeBlock(pos.down(), false);
                        LOGGER.info("[SimpleTombstone] 删除墓碑的玻璃块。");
                    }
                    world.removeBlock(pos, false);
                    TOMBSTONE_CHESTS.remove(pos);
                    RESURRECTED_PLAYERS.remove(player.getUuid());
                    storage.removeTombstone(pos);
                    LOGGER.info("[SimpleTombstone] 移除墓碑 {} 并归还物品。", pos.toShortString());
                    break;
                }
            }
        }
    }


        public record PlayerTombstoneData(UUID playerId, List<ItemStack> items) {
            public PlayerTombstoneData(UUID playerId, List<ItemStack> items) {
                this.playerId = playerId;
                this.items = new ArrayList<>(items);
            }

            @Override
            public List<ItemStack> items() {
                return Collections.unmodifiableList(items);
            }
        }
}

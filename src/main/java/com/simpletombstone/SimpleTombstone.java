package com.simpletombstone;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;

import net.minecraft.block.*;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SimpleTombstone implements ModInitializer {
    public static final String MOD_ID = "simple-tombstone";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<BlockPos, List<PlayerTombstoneData>> TOMBSTONE_CHESTS = new HashMap<>();
    private static final Set<UUID> DEAD_PLAYERS = new HashSet<>();
    private static final Set<UUID> RESURRECTED_PLAYERS = new HashSet<>();
    private static TombstoneConfig config;

    @Override
    public void onInitialize() {
        LOGGER.info("[SimpleTombstone] 服务器端初始化中...");

        config = TombstoneConfig.load();

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
            if (entity instanceof ServerPlayerEntity player) {
                if (player.getClass().getName().contains("EntityPlayerMPFake")) {
                    LOGGER.info("[SimpleTombstone] 跳过 Carpet 假人 {}", player.getName().getString());
                    return true;
                }
                LOGGER.info("[SimpleTombstone] 检测到玩家 {} 死亡，创建墓碑...", player.getName().getString());
                RESURRECTED_PLAYERS.remove(player.getUuid());
                createTombstoneForMixin(player);
                DEAD_PLAYERS.add(player.getUuid());
                return false;
            }
            return true;
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            UUID playerId = newPlayer.getUuid();
            if (DEAD_PLAYERS.contains(playerId)) {
                LOGGER.info("[SimpleTombstone] 玩家 {} 已重生，物品归还功能已启用。", newPlayer.getName().getString());
                DEAD_PLAYERS.remove(playerId);
                RESURRECTED_PLAYERS.add(playerId);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (config.checkDistanceEnabled) {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (RESURRECTED_PLAYERS.contains(player.getUuid())) {
                        checkPlayerNearTombstone(player);
                    }
                }
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            BlockState state = world.getBlockState(pos);

            if (state.getBlock() instanceof FlowerPotBlock) {
                TombstoneStorage storage = TombstoneStorage.load((ServerWorld) world);
                List<PlayerTombstoneData> dataList = storage.getTombstoneData().get(pos);
                if (dataList != null) {
                    for (PlayerTombstoneData data : dataList) {
                        if (data.playerId().equals(player.getUuid())) {
                            for (ItemStack stack : data.items()) {
                                player.getInventory().offerOrDrop(stack);
                            }
                            world.removeBlock(pos, false);
                            storage.removeTombstone(pos, player.getUuid());
                            player.sendMessage(Text.of("你的物品已经从墓碑中恢复！"), false);
                            LOGGER.info("[SimpleTombstone] 玩家 {} 恢复了物品并删除了墓碑。", player.getName().getString());
                            break;
                        }
                    }
                }
            }
            return ActionResult.PASS;
        });

        LOGGER.info("[SimpleTombstone] 服务器端初始化完成");
    }

    public static void createTombstoneForMixin(ServerPlayerEntity player) {
        BlockPos deathPos = player.getBlockPos();
        World world = player.getWorld();
        RegistryKey<World> dimension = world.getRegistryKey();

        boolean deadInVoid = false;
        while (deathPos.getY() <= world.getBottomY()) {
            deathPos = deathPos.up();
            deadInVoid = true;
        }

        if (dimension == World.END && deadInVoid) {
            deathPos = deathPos.add(0, 60, 0);
            while (!world.isAir(deathPos)) {
                deathPos = deathPos.up();
            }
        }

        while (world.isAir(deathPos.down()) && deathPos.getY() > 0) {
            deathPos = deathPos.down();
        }

        BlockPos basePos = deathPos.down();
        BlockState baseState = world.getBlockState(basePos);

        if (baseState.getFluidState().isOf(Fluids.WATER)) {
            while (world.getFluidState(deathPos).isOf(Fluids.WATER)) {
                deathPos = deathPos.up();
            }
            basePos = deathPos.down();
            world.setBlockState(basePos, Blocks.GLASS.getDefaultState());
        } else if (baseState.getFluidState().isOf(Fluids.LAVA)) {
            while (world.getFluidState(deathPos).isOf(Fluids.LAVA) && deathPos.getY() < world.getTopY()) {
                deathPos = deathPos.up();
            }
            basePos = deathPos.down();
            if (!world.getBlockState(basePos).isSolidBlock(world, basePos)) {
                world.setBlockState(basePos, Blocks.GLASS.getDefaultState());
            }
        } else if (baseState.getBlock() instanceof FluidBlock ||
                !baseState.isFullCube(world, basePos) ||
                !baseState.isSolidBlock(world, basePos)) {
            world.setBlockState(basePos, Blocks.GLASS.getDefaultState());
        }

        BlockPos tombstonePos = basePos.up();

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().removeStack(i, Integer.MAX_VALUE);
            if (!stack.isEmpty()) items.add(stack);
        }

        PlayerTombstoneData newData = new PlayerTombstoneData(player.getUuid(), items);

        TOMBSTONE_CHESTS.computeIfAbsent(tombstonePos, k -> new ArrayList<>()).add(newData);

        TombstoneStorage storage = TombstoneStorage.load((ServerWorld) world);
        storage.addTombstone(tombstonePos, newData);

        List<Block> flowerPots = Registries.BLOCK.streamEntries()
                .map(RegistryEntry::value)
                .filter(block -> block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT)
                .filter(block -> {
                    String id = Registries.BLOCK.getId(block).toString();
                    boolean inList = config.flowerPots.contains(id);
                    return config.whitelistMode == inList;
                })
                .toList();

        Block chosenPot = flowerPots.isEmpty() ? Blocks.POTTED_DANDELION : flowerPots.get(new Random().nextInt(flowerPots.size()));
        world.setBlockState(tombstonePos, chosenPot.getDefaultState());

        player.sendMessage(Text.of("A loot chest has been placed at " + tombstonePos.toShortString()), false);
        LOGGER.info("[SimpleTombstone] 为玩家 {} 在 {} 创建了墓碑。", player.getName().getString(), tombstonePos.toShortString());
    }

    private void checkPlayerNearTombstone(ServerPlayerEntity player) {
        World world = player.getWorld();
        BlockPos playerPos = player.getBlockPos();
        TombstoneStorage storage = TombstoneStorage.load((ServerWorld) world);

        for (BlockPos pos : BlockPos.iterate(
                playerPos.getX() - 4, playerPos.getY() - 4, playerPos.getZ() - 4,
                playerPos.getX() + 4, playerPos.getY() + 4, playerPos.getZ() + 4)) {
            List<PlayerTombstoneData> dataList = TOMBSTONE_CHESTS.get(pos);
            if (dataList != null) {
                Iterator<PlayerTombstoneData> it = dataList.iterator();
                while (it.hasNext()) {
                    PlayerTombstoneData data = it.next();
                    if (data.playerId().equals(player.getUuid())) {
                        for (ItemStack stack : data.items()) {
                            player.getInventory().offerOrDrop(stack);
                        }
                        pos = pos.down();
                        if (world.getBlockState(pos.down()).getBlock() == Blocks.GLASS && pos.down().getY() != world.getBottomY()) {
                            world.removeBlock(pos, false);
                        }
                        pos = pos.up();
                        world.removeBlock(pos, false);
                        it.remove();
                        RESURRECTED_PLAYERS.remove(player.getUuid());
                        storage.removeTombstone(pos, player.getUuid());
                        LOGGER.info("[SimpleTombstone] 移除墓碑 {} 并归还物品。", pos.toShortString());
                        break;
                    }
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
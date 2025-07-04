package com.simpletombstone;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;

import net.minecraft.block.*;
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
import java.util.stream.Collectors;

public class SimpleTombstone implements ModInitializer {
    public static final String MOD_ID = "simple-tombstone";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<BlockPos, PlayerTombstoneData> TOMBSTONE_CHESTS = new HashMap<>();
    private static final Set<UUID> DEAD_PLAYERS = new HashSet<>();
    private static final Set<UUID> RESURRECTED_PLAYERS = new HashSet<>();
    private static TombstoneConfig config;

    @Override
    public void onInitialize() {
        LOGGER.info("[SimpleTombstone] 服务器端初始化中...");

        config = TombstoneConfig.load();

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
            if (entity instanceof ServerPlayerEntity player) {
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
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (RESURRECTED_PLAYERS.contains(player.getUuid())) {
                    checkPlayerNearTombstone(player);
                }
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            BlockState state = world.getBlockState(pos);

            if (state.getBlock() == Blocks.FLOWER_POT) {
                TombstoneStorage storage = TombstoneStorage.load((ServerWorld) world);
                for (Map.Entry<BlockPos, PlayerTombstoneData> entry : storage.getTombstoneData().entrySet()) {
                    if (entry.getKey().equals(pos)) {
                        PlayerTombstoneData data = entry.getValue();
                        for (ItemStack stack : data.items()) {
                            player.getInventory().offerOrDrop(stack);
                        }
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

    public static void createTombstoneForMixin(ServerPlayerEntity player) {
        BlockPos deathPos = player.getBlockPos();
        World world = player.getWorld();
        RegistryKey<World> dimension = world.getRegistryKey();

        boolean deadinvoid = false;
        while (deathPos.getY() <= world.getBottomY()) {
            deathPos = deathPos.up();
            deadinvoid = true;
        }
        deathPos = deathPos.up();
        while (world.isAir(deathPos.down()) && deathPos.getY() > 0) {
            deathPos = deathPos.down();
        }
        deathPos = deathPos.down();

        if (dimension == World.END && deadinvoid) {
            deathPos = deathPos.add(0, 60, 0);
            while (!world.isAir(deathPos.down())) {
                deathPos = deathPos.up();
            }
        }

        if (world.getBlockState(deathPos.down()).getBlock() instanceof FluidBlock) {
            world.setBlockState(deathPos, Blocks.GLASS.getDefaultState());
        } else if (!world.getBlockState(deathPos).isFullCube(world, deathPos)) {
            world.setBlockState(deathPos, Blocks.GLASS.getDefaultState());
        }

        BlockPos tombstonePos = deathPos.up();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().removeStack(i, Integer.MAX_VALUE);
            if (!stack.isEmpty()) items.add(stack);
        }

        PlayerTombstoneData tombstoneData = new PlayerTombstoneData(player.getUuid(), items);
        PlayerTombstoneData existing = TOMBSTONE_CHESTS.get(tombstonePos);

        if (existing != null && existing.playerId().equals(player.getUuid())) {
            List<ItemStack> merged = new ArrayList<>(existing.items());
            merged.addAll(items);
            TOMBSTONE_CHESTS.put(tombstonePos, new PlayerTombstoneData(player.getUuid(), merged));
        } else {
            TOMBSTONE_CHESTS.put(tombstonePos, tombstoneData);
        }

        TombstoneStorage storage = TombstoneStorage.load((ServerWorld) world);
        storage.addTombstone(tombstonePos, tombstoneData);

        List<Block> flowerPots = new ArrayList<>();
        Iterable<RegistryEntry<Block>> blockEntries = Registries.BLOCK.streamEntries().collect(Collectors.toList());
        for (RegistryEntry<Block> entry : blockEntries) {
            Block block = entry.value();
            if (block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT) {
                String registryName = Registries.BLOCK.getId(block).toString();
                boolean matches = config.flowerPots.contains(registryName);
                if ((config.whitelistMode && matches) || (!config.whitelistMode && !matches)) {
                    flowerPots.add(block);
                }
            }
        }

        Block chosen = flowerPots.isEmpty() ? Blocks.POTTED_DANDELION : flowerPots.get(new Random().nextInt(flowerPots.size()));
        world.setBlockState(tombstonePos, chosen.getDefaultState());

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
                    for (ItemStack stack : data.items()) {
                        player.getInventory().offerOrDrop(stack);
                    }
                    pos = pos.down();
                    if (world.getBlockState(pos.down()).getBlock() == Blocks.GLASS && pos.down().getY() != world.getBottomY()) {
                        world.removeBlock(pos, false);
                    }
                    pos = pos.up();
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

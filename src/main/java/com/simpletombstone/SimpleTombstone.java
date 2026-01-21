package com.simpletombstone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
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

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        TombstoneCommand.register(dispatcher)
        );

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
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }

            BlockPos pos = hitResult.getBlockPos();
            BlockState state = world.getBlockState(pos);

            if (state.getBlock() instanceof FlowerPotBlock) {
                boolean success = triggerReturn(
                        (ServerWorld) world,
                        pos,
                        serverPlayer
                );

                if (success) {
                    LOGGER.info(
                            "[SimpleTombstone] 玩家 {} 通过交互恢复了墓碑物品",
                            serverPlayer.getName().getString()
                    );
                    return ActionResult.SUCCESS;
                }
            }

            return ActionResult.PASS;
        });

        LOGGER.info("[SimpleTombstone] 服务器端初始化完成");
    }

    public static void createTombstoneForMixin(ServerPlayerEntity player) {
        BlockPos deathPos = player.getBlockPos();
        ServerWorld world = player.getEntityWorld();
        RegistryKey<World> dimension = world.getRegistryKey();

        boolean deadInVoid = false;
        if (deathPos.getY() <= world.getBottomY()) {
            //LOGGER.info("death in void.Location:{}", deathPos);
            deadInVoid = true;
            while (deathPos.getY() <= world.getBottomY()) {
                deathPos = deathPos.up();
            }
        }

        boolean deadInEnd = false;
        if (dimension == World.END && deadInVoid) {
            //LOGGER.info("death in end.Location:{}", deathPos);
            deadInEnd=true;
            deathPos = deathPos.add(0,60,0);
            //LOGGER.info("relocate death position in end.New location:{}",deathPos);
            while (!world.isAir(deathPos)) {
                deathPos = deathPos.up();
            }
        }
        if(!deadInVoid){
            while (world.isAir(deathPos.down()) && deathPos.getY() > 0) {
                deathPos = deathPos.down();
            }
        }

        BlockPos basePos = deathPos.down();
        BlockState baseState = world.getBlockState(basePos);

        if (world.getFluidState(deathPos).isOf(Fluids.WATER)|| world.getFluidState(deathPos).isOf(Fluids.FLOWING_WATER)) {
            while ((world.getFluidState(deathPos).isOf(Fluids.WATER)|| world.getFluidState(deathPos).isOf(Fluids.FLOWING_WATER) && deathPos.getY() < world.getTopY(Heightmap.Type.WORLD_SURFACE, deathPos))) {
                deathPos = deathPos.up();
            }
            basePos = deathPos.down();
            BlockState below = world.getBlockState(basePos);
            if (below.getFluidState().isOf(Fluids.WATER) || below.getFluidState().isOf(Fluids.FLOWING_WATER) || !below.isSolidBlock(world, basePos)) {
                world.setBlockState(basePos, Blocks.GLASS.getDefaultState());
            }
        } else if (world.getFluidState(deathPos).isOf(Fluids.LAVA)|| world.getFluidState(deathPos).isOf(Fluids.FLOWING_LAVA)) {
            while ((world.getFluidState(deathPos).isOf(Fluids.LAVA)|| world.getFluidState(deathPos).isOf(Fluids.FLOWING_LAVA)) && deathPos.getY() < world.getTopY(Heightmap.Type.WORLD_SURFACE, deathPos)) {
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

        if (deadInEnd) {
            placeEndPlatformIfPossible(world,basePos);
        }

        BlockPos tombstonePos = basePos.up();

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().removeStack(i, Integer.MAX_VALUE);
            if (!stack.isEmpty()) items.add(stack);
        }

        // get experience
        int expLevel = 0;
        float expProgress = 0.0f;
        if (config.saveExperience) {
            expLevel = player.experienceLevel;
            expProgress = player.experienceProgress;
            
            // set player experience to 0
            player.addExperience(-player.totalExperience);
        }

        // tombstone is empty
        if (items.isEmpty() && (!config.saveExperience || (expLevel == 0 && expProgress == 0.0f))) {
            LOGGER.info("[SimpleTombstone] 玩家 {} 没有任何物品或经验，跳过创建墓碑", player.getName().getString());
            return; // uncreate tombstone
        }

        PlayerTombstoneData newData = new PlayerTombstoneData(player.getUuid(), items, expLevel, expProgress);

        TOMBSTONE_CHESTS.computeIfAbsent(tombstonePos, k -> new ArrayList<>()).add(newData);

        TombstoneStorage storage = TombstoneStorage.load(world);
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
        ServerWorld world = player.getEntityWorld();
        BlockPos playerPos = player.getBlockPos();
        TombstoneStorage storage = TombstoneStorage.load(world);

        for (BlockPos pos : BlockPos.iterate(
                playerPos.getX() - 4, playerPos.getY() - 4, playerPos.getZ() - 4,
                playerPos.getX() + 4, playerPos.getY() + 4, playerPos.getZ() + 4)) {
            List<PlayerTombstoneData> dataList = TOMBSTONE_CHESTS.get(pos);
            if (dataList != null) {
                Iterator<PlayerTombstoneData> it = dataList.iterator();
                while (it.hasNext()) {
                    PlayerTombstoneData data = it.next();
                    if (data.playerId().equals(player.getUuid())) {
                        if (!canInventoryFitAll(player, data.items())) {
                            return;
                        }
                        for (ItemStack stack : data.items()) {
                            player.getInventory().offerOrDrop(stack);
                        }
                        
                        // return experience
                        if (config.saveExperience) {
                            player.addExperienceLevels(data.expLevel());
                            player.addExperience(Math.round(player.getNextLevelExperience() * data.expProgress()));
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
                        LOGGER.info("[SimpleTombstone] 移除墓碑 {} 并归还物品和经验值。", pos.toShortString());
                        break;
                    }
                }
            }
        }
    }

    public record PlayerTombstoneData(UUID playerId, List<ItemStack> items, int expLevel, float expProgress) {
        public PlayerTombstoneData(UUID playerId, List<ItemStack> items, int expLevel, float expProgress) {
            this.playerId = playerId;
            this.items = new ArrayList<>(items);
            this.expLevel = expLevel;
            this.expProgress = expProgress;
        }

        @Override
        public List<ItemStack> items() {
            return Collections.unmodifiableList(items);
        }

        public static final Codec<PlayerTombstoneData> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Uuids.CODEC.fieldOf("playerId").forGetter(PlayerTombstoneData::playerId),
                        ItemStack.CODEC.listOf().fieldOf("items").forGetter(PlayerTombstoneData::items),
                        Codec.INT.fieldOf("expLevel").forGetter(PlayerTombstoneData::expLevel),
                        Codec.FLOAT.fieldOf("expProgress").forGetter(PlayerTombstoneData::expProgress)
                ).apply(instance, PlayerTombstoneData::new)
        );
    }

    private boolean canInventoryFitAll(ServerPlayerEntity player, List<ItemStack> items) {
        var inventory = player.getInventory();

        ItemStack[] simulated = new ItemStack[inventory.size()];
        for (int i = 0; i < inventory.size(); i++) {
            simulated[i] = inventory.getStack(i).copy();
        }

        for (ItemStack stack : items) {
            ItemStack remaining = stack.copy();

            for (int i = 0; i < simulated.length && !remaining.isEmpty(); i++) {
                ItemStack slot = simulated[i];
                if (!slot.isEmpty()
                        && ItemStack.areItemsAndComponentsEqual(slot, remaining)
                        && slot.getCount() < slot.getMaxCount()) {

                    int transferable = Math.min(
                            slot.getMaxCount() - slot.getCount(),
                            remaining.getCount()
                    );
                    slot.increment(transferable);
                    remaining.decrement(transferable);
                }
            }

            for (int i = 0; i < simulated.length && !remaining.isEmpty(); i++) {
                if (simulated[i].isEmpty()) {
                    simulated[i] = remaining.copy();
                    remaining = ItemStack.EMPTY;
                }
            }

            if (!remaining.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    public static boolean triggerReturn(
            ServerWorld world,
            BlockPos pos,
            ServerPlayerEntity player
    ) {
        TombstoneStorage storage = TombstoneStorage.get(world);
        List<PlayerTombstoneData> dataList = storage.getTombstoneData().get(pos);

        if (dataList == null || dataList.isEmpty()) {
            return false;
        }

        Iterator<PlayerTombstoneData> it = dataList.iterator();
        while (it.hasNext()) {
            PlayerTombstoneData data = it.next();

            if (!data.playerId().equals(player.getUuid())) {
                continue;
            }

            // 归还物品
            for (ItemStack stack : data.items()) {
                player.getInventory().offerOrDrop(stack.copy());
            }

            // 归还经验
            if (TombstoneConfig.load().saveExperience) {
                player.addExperienceLevels(data.expLevel());
                int exp = Math.round(player.getNextLevelExperience() * data.expProgress());
                player.addExperience(exp);
            }

            // 移除该玩家的墓碑数据
            it.remove();
            storage.markDirty();

            // 如果这个位置已经没有任何墓碑数据 → 移除方块
            if (dataList.isEmpty()) {
                world.removeBlock(pos, false);
            }

            player.sendMessage(Text.literal("你的物品和经验值已经从墓碑中恢复！"), false);

            return true;
        }

        return false;
    }

    private static void placeEndPlatformIfPossible(ServerWorld world, BlockPos basePos) {

        BlockPos[] glassPositions = {
                basePos.add( 1, 0,  0),
                basePos.add(-1, 0,  0),
                basePos.add( 0, 0,  1),
                basePos.add( 0, 0, -1),
                basePos.add( 1, 0,  1),
                basePos.add( 1, 0, -1),
                basePos.add(-1, 0,  1),
                basePos.add(-1, 0, -1)
        };

        BlockPos[] torchPositions = {
                basePos.add( 1, 1,  1),
                basePos.add( 1, 1, -1),
                basePos.add(-1, 1,  1),
                basePos.add(-1, 1, -1)
        };

        for (BlockPos pos : glassPositions) {
            if (!world.getBlockState(pos).isAir()) {
                return;
            }
        }
        for (BlockPos pos : torchPositions) {
            if (!world.getBlockState(pos).isAir()) {
                return;
            }
        }

        for (BlockPos pos : glassPositions) {
            world.setBlockState(pos, Blocks.GLASS.getDefaultState());
        }

        for (BlockPos pos : torchPositions) {
            world.setBlockState(pos, Blocks.TORCH.getDefaultState());
        }
    }
}
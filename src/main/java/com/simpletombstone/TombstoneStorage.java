package com.simpletombstone;

import net.minecraft.nbt.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TombstoneStorage extends PersistentState {
    private static final String KEY_TOMBSTONES = "Tombstones";
    private static final String KEY_POS = "Pos";
    private static final String KEY_ITEMS = "Items";
    private static final String KEY_PLAYER_ID = "PlayerId";
    private static final Logger LOGGER = LoggerFactory.getLogger(TombstoneStorage.class);

    // 更改为每个位置存储多个玩家的墓碑数据
    private final Map<BlockPos, List<SimpleTombstone.PlayerTombstoneData>> tombstoneData = new HashMap<>();
    private final TombstoneConfig config;

    public TombstoneStorage() {
        this.config = TombstoneConfig.load();
    }

    public static TombstoneStorage load(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                new PersistentState.Type<>(
                        TombstoneStorage::new,
                        TombstoneStorage::fromNbt,
                        null
                ),
                "simple_tombstone"
        );
    }

    public void addTombstone(BlockPos pos, SimpleTombstone.PlayerTombstoneData data) {
        LOGGER.info("[TombstoneStorage] 添加墓碑数据: {}", pos.toShortString());

        List<SimpleTombstone.PlayerTombstoneData> existingList = tombstoneData.getOrDefault(pos, new ArrayList<>());
        boolean merged = false;

        for (int i = 0; i < existingList.size(); i++) {
            SimpleTombstone.PlayerTombstoneData existing = existingList.get(i);
            if (existing.playerId().equals(data.playerId())) {
                List<ItemStack> mergedItems = new ArrayList<>(existing.items());
                mergedItems.addAll(data.items());
                existingList.set(i, new SimpleTombstone.PlayerTombstoneData(existing.playerId(), mergedItems));
                LOGGER.info("[TombstoneStorage] 合并同玩家墓碑数据: {}", pos.toShortString());
                merged = true;
                break;
            }
        }

        if (!merged) {
            existingList.add(data);
            LOGGER.info("[TombstoneStorage] 添加新玩家墓碑记录: {}", pos.toShortString());
        }

        tombstoneData.put(pos, existingList);

        // 限制玩家最大墓碑数
        if (config.maxTombstonesPerPlayer > 0) {
            List<Map.Entry<BlockPos, SimpleTombstone.PlayerTombstoneData>> all = new ArrayList<>();
            for (Map.Entry<BlockPos, List<SimpleTombstone.PlayerTombstoneData>> entry : tombstoneData.entrySet()) {
                for (SimpleTombstone.PlayerTombstoneData d : entry.getValue()) {
                    if (d.playerId().equals(data.playerId())) {
                        all.add(new AbstractMap.SimpleEntry<>(entry.getKey(), d));
                    }
                }
            }

            if (all.size() > config.maxTombstonesPerPlayer) {
                Map.Entry<BlockPos, SimpleTombstone.PlayerTombstoneData> oldest = all.get(0);
                tombstoneData.get(oldest.getKey()).remove(oldest.getValue());
                if (tombstoneData.get(oldest.getKey()).isEmpty()) {
                    tombstoneData.remove(oldest.getKey());
                }
                LOGGER.warn("达到玩家墓碑上限({})，删除最老墓碑: {}", config.maxTombstonesPerPlayer, oldest.getKey().toShortString());
            }
        }

        markDirty();
    }

    public void removeTombstone(BlockPos pos, UUID playerId) {
        List<SimpleTombstone.PlayerTombstoneData> list = tombstoneData.get(pos);
        if (list != null) {
            list.removeIf(data -> data.playerId().equals(playerId));
            if (list.isEmpty()) {
                tombstoneData.remove(pos);
            }
            LOGGER.info("[TombstoneStorage] 移除玩家 {} 的墓碑数据: {}", playerId, pos.toShortString());
            markDirty();
        }
    }

    public Map<BlockPos, List<SimpleTombstone.PlayerTombstoneData>> getTombstoneData() {
        return tombstoneData;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList tombstoneList = new NbtList();
        for (Map.Entry<BlockPos, List<SimpleTombstone.PlayerTombstoneData>> entry : tombstoneData.entrySet()) {
            for (SimpleTombstone.PlayerTombstoneData data : entry.getValue()) {
                NbtCompound tombstoneTag = new NbtCompound();
                tombstoneTag.put(KEY_POS, NbtHelper.fromBlockPos(entry.getKey()));
                tombstoneTag.putUuid(KEY_PLAYER_ID, data.playerId());

                NbtList itemList = new NbtList();
                for (ItemStack stack : data.items()) {
                    itemList.add(stack.writeNbt(new NbtCompound()));
                }
                tombstoneTag.put(KEY_ITEMS, itemList);

                tombstoneList.add(tombstoneTag);
            }
        }
        nbt.put(KEY_TOMBSTONES, tombstoneList);
        return nbt;
    }

    public static TombstoneStorage fromNbt(NbtCompound nbt) {
        TombstoneStorage storage = new TombstoneStorage();
        NbtList tombstoneList = nbt.getList(KEY_TOMBSTONES, NbtElement.COMPOUND_TYPE);

        for (NbtElement element : tombstoneList) {
            NbtCompound tombstoneTag = (NbtCompound) element;
            BlockPos pos = NbtHelper.toBlockPos(tombstoneTag.getCompound(KEY_POS));
            UUID playerId = tombstoneTag.getUuid(KEY_PLAYER_ID);

            List<ItemStack> items = new ArrayList<>();
            NbtList itemList = tombstoneTag.getList(KEY_ITEMS, NbtElement.COMPOUND_TYPE);
            for (NbtElement itemElement : itemList) {
                items.add(ItemStack.fromNbt((NbtCompound) itemElement));
            }

            storage.tombstoneData.computeIfAbsent(pos, k -> new ArrayList<>())
                    .add(new SimpleTombstone.PlayerTombstoneData(playerId, items));
        }

        return storage;
    }
}
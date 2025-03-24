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

    private final Map<BlockPos, SimpleTombstone.PlayerTombstoneData> tombstoneData = new HashMap<>();

    public static TombstoneStorage load(ServerWorld world) {
        LOGGER.info("[TombstoneStorage] 加载墓碑数据...");
        TombstoneStorage storage = world.getPersistentStateManager().getOrCreate(
                new PersistentState.Type<>(
                        TombstoneStorage::new,  // Supplier
                        TombstoneStorage::fromNbt,           // NBT加载函数
                        null                           // 可选的DataFixTypes（版本升级用）
                ),
                "simple_tombstone"                 // 存储名称
        );
        LOGGER.info("[TombstoneStorage] 成功加载墓碑数据.");
        return storage;
    }

    public void addTombstone(BlockPos pos, SimpleTombstone.PlayerTombstoneData data) {
        LOGGER.info("[TombstoneStorage] 添加墓碑数据: {}", pos.toShortString());
        tombstoneData.put(pos, data);
        markDirty();  // 确保数据被保存
    }

    public void removeTombstone(BlockPos pos) {
        LOGGER.info("[TombstoneStorage] 移除墓碑数据: {}", pos.toShortString());
        tombstoneData.remove(pos);
        markDirty();  // 确保数据被保存
    }

    public Map<BlockPos, SimpleTombstone.PlayerTombstoneData> getTombstoneData() {
        return tombstoneData;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        LOGGER.info("[TombstoneStorage] 写入墓碑数据到 NBT...");
        NbtList tombstoneList = new NbtList();
        for (Map.Entry<BlockPos, SimpleTombstone.PlayerTombstoneData> entry : tombstoneData.entrySet()) {
            NbtCompound tombstoneTag = new NbtCompound();
            tombstoneTag.put(KEY_POS, NbtHelper.fromBlockPos(entry.getKey()));
            tombstoneTag.putUuid(KEY_PLAYER_ID, entry.getValue().playerId());

            NbtList itemList = new NbtList();
            for (ItemStack stack : entry.getValue().items()) {
                NbtCompound stackTag = stack.writeNbt(new NbtCompound());
                itemList.add(stackTag);
            }
            tombstoneTag.put(KEY_ITEMS, itemList);
            tombstoneList.add(tombstoneTag);
        }
        nbt.put(KEY_TOMBSTONES, tombstoneList);

        // 打印完整的NBT数据以检查格式
        LOGGER.info("[TombstoneStorage] 写入的 NBT 数据: {}", nbt);
        LOGGER.info("[TombstoneStorage] NBT 数据写入成功.");
        return nbt;
    }

    public static TombstoneStorage fromNbt(NbtCompound nbt) {
        //LOGGER.info("[TombstoneStorage] 从 NBT 加载墓碑数据...");
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

            storage.tombstoneData.put(pos, new SimpleTombstone.PlayerTombstoneData(playerId, items));
            LOGGER.info("[TombstoneStorage] 加载墓碑: {}，玩家ID: {}", pos.toShortString(), playerId);
        }

        //LOGGER.info("[TombstoneStorage] 成功加载墓碑数据.");
        return storage;
    }
}

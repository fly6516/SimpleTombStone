package com.simpletombstone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public final class TombstoneStorage extends PersistentState {

    private static final Logger LOGGER = LoggerFactory.getLogger("SimpleTombstone");
    private static final String KEY_TOMBSTONES = "tombstones";

    /**
     * Map<String, List<PlayerTombstoneData>> 保存为存档使用
     * key = "x,y,z"
     */
    private final Map<String, List<SimpleTombstone.PlayerTombstoneData>> tombstones;
    private final TombstoneConfig config;

    /* ===========================
       ========== CODEC ==========
       =========================== */

    public static final Codec<TombstoneStorage> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.unboundedMap(
                                    Codec.STRING,
                                    SimpleTombstone.PlayerTombstoneData.CODEC.listOf()
                            )
                            .fieldOf(KEY_TOMBSTONES)
                            .forGetter(storage -> storage.tombstones)
            ).apply(instance, TombstoneStorage::new));

    /* ===========================
       ===== PersistentState =====
       =========================== */

    public static final PersistentStateType<TombstoneStorage> TYPE =
            new PersistentStateType<>(
                    "simple_tombstone",
                    TombstoneStorage::new,
                    CODEC,
                    null
            );

    public static TombstoneStorage get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TYPE);
    }

    public static TombstoneStorage load(ServerWorld world) {
        return get(world);
    }

    /* ===========================
       ===== Constructors ========
       =========================== */

    public TombstoneStorage() {
        this(new HashMap<>());
    }

    private TombstoneStorage(Map<String, List<SimpleTombstone.PlayerTombstoneData>> tombstones) {
        this.tombstones = new HashMap<>();
        // 将每个List都转成ArrayList，保证可修改
        for (var entry : tombstones.entrySet()) {
            this.tombstones.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        this.config = TombstoneConfig.load();
    }

    /* ===========================
       ===== Business Logic ======
       =========================== */

    public void addTombstone(BlockPos pos, SimpleTombstone.PlayerTombstoneData data) {
        String key = posToString(pos);
        LOGGER.debug("Add tombstone at {}", key);

        List<SimpleTombstone.PlayerTombstoneData> list =
                tombstones.computeIfAbsent(key, k -> new ArrayList<>());

        boolean merged = false;
        for (int i = 0; i < list.size(); i++) {
            var existing = list.get(i);
            if (existing.playerId().equals(data.playerId())) {
                List<ItemStack> mergedItems = new ArrayList<>(existing.items());
                mergedItems.addAll(data.items());
                list.set(i, new SimpleTombstone.PlayerTombstoneData(
                        existing.playerId(),
                        mergedItems
                ));
                merged = true;
                break;
            }
        }

        if (!merged) {
            list.add(data);
        }

        enforcePlayerLimit(data.playerId());
        markDirty();
    }

    public void removeTombstone(BlockPos pos, UUID playerId) {
        String key = posToString(pos);
        List<SimpleTombstone.PlayerTombstoneData> list = tombstones.get(key);
        if (list == null) return;

        list.removeIf(data -> data.playerId().equals(playerId));
        if (list.isEmpty()) {
            tombstones.remove(key);
        }

        markDirty();
    }

    public Map<BlockPos, List<SimpleTombstone.PlayerTombstoneData>> getTombstoneData() {
        return Collections.unmodifiableMap(
                tombstones.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> stringToPos(e.getKey()),
                                Map.Entry::getValue
                        ))
        );
    }

    /* ===========================
       ===== Helpers =============
       =========================== */

    private void enforcePlayerLimit(UUID playerId) {
        if (config.maxTombstonesPerPlayer <= 0) return;

        List<Map.Entry<String, SimpleTombstone.PlayerTombstoneData>> all = new ArrayList<>();
        for (var entry : tombstones.entrySet()) {
            for (var data : entry.getValue()) {
                if (data.playerId().equals(playerId)) {
                    all.add(Map.entry(entry.getKey(), data));
                }
            }
        }

        if (all.size() <= config.maxTombstonesPerPlayer) return;

        var oldest = all.getFirst();
        tombstones.get(oldest.getKey()).remove(oldest.getValue());
        if (tombstones.get(oldest.getKey()).isEmpty()) {
            tombstones.remove(oldest.getKey());
        }

        LOGGER.warn("Player {} exceeded tombstone limit, removed oldest", playerId);
    }

    private static String posToString(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static BlockPos stringToPos(String key) {
        String[] parts = key.split(",");
        return new BlockPos(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])
        );
    }
}
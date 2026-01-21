package com.simpletombstone;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.simpletombstone.SimpleTombstone.PlayerTombstoneData;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
// import java.util.UUID;

import static com.simpletombstone.SimpleTombstone.triggerReturn;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class TombstoneCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {

        dispatcher.register(
                literal("tombstone")
                        .then(literal("reload").executes(ctx -> reload(ctx.getSource())))
                        .then(literal("list")
                                .executes(ctx -> list(ctx.getSource(), null))
                                .then(argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                                ctx.getSource().getServer().getPlayerNames(), builder
                                        ))
                                        .executes(ctx -> list(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")
                                        ))
                                )
                        )
//                        .then(literal("clear")
//                                .then(argument("player", StringArgumentType.word())
//                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
//                                                ctx.getSource().getServer().getPlayerNames(), builder
//                                        ))
//                                        .executes(ctx -> clear(
//                                                ctx.getSource(),
//                                                StringArgumentType.getString(ctx, "player")
//                                        ))
//                                )
//                        )
                        .then(literal("config").executes(ctx -> config(ctx.getSource())))
                        .then(literal("trigger")
                                .then(argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                                ctx.getSource().getServer().getPlayerNames(), builder
                                        ))
                                        .executes(ctx -> trigger(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")
                                        ))
                                )
                        )
                        .then(literal("me").executes(ctx -> list(ctx.getSource(),
                                ctx.getSource().getPlayer() != null ?
                                        ctx.getSource().getPlayer().getName().getString() : null
                        )))
        );
    }

    /* ===========================
       ===== Command Logic =======
       =========================== */

    private static int reload(ServerCommandSource source) {
        TombstoneConfig.reload();
        source.sendFeedback(() -> Text.literal("§a[Tombstone] 配置已重载"), true);
        return 1;
    }

    private static int list(ServerCommandSource source, String playerName) {
        TombstoneStorage storage = TombstoneStorage.get(source.getWorld());
        Map<BlockPos, List<PlayerTombstoneData>> all = storage.getTombstoneData();

        if (all.isEmpty()) {
            source.sendFeedback(() -> Text.literal("§e[Tombstone] 当前没有墓碑"), false);
            return 0;
        }

        int count = 0;
        source.sendFeedback(() -> Text.literal("§a[Tombstone] 当前墓碑列表:"), false);

        for (Map.Entry<BlockPos, List<PlayerTombstoneData>> entry : all.entrySet()) {
            BlockPos pos = entry.getKey();
            List<PlayerTombstoneData> dataList = entry.getValue();

            if (playerName != null) {
                boolean match = false;
                for (PlayerTombstoneData data : dataList) {
                    ServerPlayerEntity p = source.getServer().getPlayerManager().getPlayer(playerName);
                    if (p != null && data.playerId().equals(p.getUuid())) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
            }

            source.sendFeedback(() ->
                            Text.literal(" - " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()),
                    false
            );
            count++;
        }

        if (count == 0 && playerName != null) {
            source.sendFeedback(() ->
                    Text.literal("§e[Tombstone] 玩家 " + playerName + " 当前没有墓碑"), false);
        }

        return count;
    }

//    private static int clear(ServerCommandSource source, String playerName) {
//        ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(playerName);
//
//        if (player == null) {
//            source.sendError(Text.literal("§c玩家不存在或不在线"));
//            return 0;
//        }
//
//        UUID uuid = player.getUuid();
//        TombstoneStorage storage = TombstoneStorage.get(source.getWorld());
//
//        int removed = 0;
//
//        Map<BlockPos, List<PlayerTombstoneData>> all = storage.getTombstoneData();
//        for (Map.Entry<BlockPos, List<PlayerTombstoneData>> entry : all.entrySet()) {
//            BlockPos pos = entry.getKey();
//            List<PlayerTombstoneData> dataList = entry.getValue();
//
//            boolean deleted = dataList.removeIf(data -> data.playerId().equals(uuid));
//
//            if (deleted) {
//                removed++;
//                // 如果该位置没有剩余玩家数据，可以选择彻底删除位置
//                if (dataList.isEmpty()) {
//                    storage.removeTombstone(pos, null); // null 表示移除整个墓碑
//                }
//            }
//        }
//
//        int finalRemoved = removed;
//        source.sendFeedback(() ->
//                        Text.literal("§a[Tombstone] 已清除 " + playerName + " 的墓碑数据，共删除 " + finalRemoved + " 个墓碑"),
//                true
//        );
//        return removed;
//    }

    private static int config(ServerCommandSource source) {
        TombstoneConfig cfg = TombstoneConfig.get();

        source.sendFeedback(() -> Text.literal("§a[Tombstone] 当前配置:"), false);
        source.sendFeedback(() ->
                        Text.literal(" - maxTombstonesPerPlayer: " + cfg.maxTombstonesPerPlayer),
                false
        );
        source.sendFeedback(() ->
                        Text.literal(" - checkDistanceEnabled: " + cfg.checkDistanceEnabled),
                false
        );
        source.sendFeedback(() ->
                        Text.literal(" - saveExperience: " + cfg.saveExperience),
                false
        );
        source.sendFeedback(() ->
                        Text.literal(" - whitelistMode: " + cfg.whitelistMode),
                false
        );
        source.sendFeedback(() ->
                        Text.literal(" - flowerPots: " + cfg.flowerPots),
                false
        );

        return 1;
    }

    private static int trigger(ServerCommandSource source, String playerName) {
        ServerWorld world = source.getWorld();
        ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(playerName);

        if (player == null) {
            source.sendError(Text.literal("找不到玩家: " + playerName));
            return 0;
        }

        TombstoneStorage storage = TombstoneStorage.get(world);
        int count = 0;

        for (BlockPos pos : storage.getTombstoneData().keySet()) {
            if (triggerReturn(world, pos, player)) {
                count++;
            }
        }

        int finalCount = count;
        source.sendFeedback(
                () -> Text.literal("已为玩家 " + playerName + " 触发 " + finalCount + " 个墓碑返还"),
                false
        );

        return count;
    }
}

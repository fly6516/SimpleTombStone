package com.simpletombstone;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;

// 新增导入语句
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;

public class SimpleTombstone implements ModInitializer {
    public static final String MOD_ID = "simple-tombstone";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // 自定义墓碑方块
	public static final Block LOOT_CHEST = Blocks.CHEST; // 修改为原版箱子

    @Override
    public void onInitialize() {
        // 将事件注册移到服务端环境判断块内
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            ServerPlayerEvents.ALLOW_DEATH.register((player, damageSource, damageAmount) -> {
                createTombstoneForMixin(player);
                player.getInventory().clear();  // 需要手动清空背包
                return false; // 阻止物品掉落
            });
            LOGGER.info("服务端初始化完成");
        } else {
            LOGGER.info("客户端初始化完成");
        }
    }

    /**
     * 供 Mixin 调用，在玩家死亡时创建物品箱
     */
    public static void createTombstoneForMixin(ServerPlayerEntity player) {
        BlockPos deathPos = player.getBlockPos();
        World world = player.getWorld();

        // 检查位置是否为空
        if (!world.isAir(deathPos)) {
            return;
        }

        // 放置箱子方块
        world.setBlockState(deathPos, LOOT_CHEST.getDefaultState());

        // 存储物品到箱子
        if (world.getBlockEntity(deathPos) instanceof ChestBlockEntity chestEntity) {
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().removeStack(i, Integer.MAX_VALUE);
                if (!stack.isEmpty()) {
                    chestEntity.setStack(i,stack);
                }
            }
        }

        // 发送消息和日志
        player.sendMessage(Text.of("A loot chest has been placed at " + deathPos.toShortString()), false);
        LOGGER.info("为玩家 {} 在 {} 创建了物品箱。", player.getName().getString(), deathPos.toShortString());
    }
}
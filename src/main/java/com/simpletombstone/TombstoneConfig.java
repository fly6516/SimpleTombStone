package com.simpletombstone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置类用于管理模组的配置文件
 * 默认配置文件路径：config/simpletombstone.json
 * 默认值：黑名单模式，花盆列表为空
 */
public class TombstoneConfig {
    // 添加maxTombstonesPerPlayer配置项
    public int maxTombstonesPerPlayer = 0;

    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("simpletombstone.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // 添加日志记录器
    private static final Logger LOGGER = LoggerFactory.getLogger(TombstoneConfig.class);
    
    /**
     * 白名单/黑名单模式
     * true: 白名单模式 - 仅允许配置的花盆类型
     * false: 黑名单模式 - 禁止配置的花盆类型
     * 默认为黑名单模式
     */
    public boolean whitelistMode = false;
    
    /**
     * 花盆列表
     * 包含要包含或排除的花盆名称
     * 支持完整命名空间格式（如 "minecraft:flower_pot"）
     * 默认为空数组
     */
    public List<String> flowerPots = new ArrayList<>();

    /**
     * 加载配置
     * 如果配置文件不存在，则从默认配置创建
     * @return 加载的配置对象
     */
    public static TombstoneConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                // 创建默认配置
                saveDefaultConfig();
            }
            
            Reader reader = new FileReader(CONFIG_PATH.toFile());
            TombstoneConfig config = GSON.fromJson(reader, TombstoneConfig.class);
            reader.close();
            return config;
        } catch (Exception e) {
            LOGGER.error("加载配置时发生错误", e);
            return new TombstoneConfig(); // 返回默认配置
        }
    }

    /**
     * 保存配置到文件
     */
    public void save() {
        try {
            Writer writer = new FileWriter(CONFIG_PATH.toFile());
            GSON.toJson(this, writer);
            writer.close();
        } catch (IOException e) {
            LOGGER.error("保存配置时发生错误", e);
        }
    }

    /**
     * 创建默认配置文件
     */
    private static void saveDefaultConfig() throws IOException {
        // 从资源目录复制默认配置
        InputStream in = SimpleTombstone.class.getClassLoader().getResourceAsStream("defaultconfigs/simpletombstone.json");
        if (in != null) {
            Files.copy(in, CONFIG_PATH);
            in.close();
        } else {
            // 如果没有找到默认配置资源，创建一个空的
            TombstoneConfig config = new TombstoneConfig();
            config.save();
        }
    }
}

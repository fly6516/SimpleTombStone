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
    // 单例缓存
    private static TombstoneConfig INSTANCE;

    // 配置项
    public int maxTombstonesPerPlayer = 0;
    public boolean checkDistanceEnabled = true;
    public boolean saveExperience = true;
    public boolean whitelistMode = false;
    public List<String> flowerPots = new ArrayList<>();

    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("simpletombstone.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(TombstoneConfig.class);

    /**
     * 获取当前配置单例
     */
    public static TombstoneConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    /**
     * 重新加载配置文件
     */
    public static void reload() {
        INSTANCE = load();
        LOGGER.info("[SimpleTombstone] 配置已重新加载");
    }

    /**
     * 加载配置文件，如果不存在则创建默认配置
     * @return 配置对象
     */
    public static TombstoneConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                saveDefaultConfig();
            }

            Reader reader = new FileReader(CONFIG_PATH.toFile());
            TombstoneConfig config = GSON.fromJson(reader, TombstoneConfig.class);
            reader.close();
            return config;
        } catch (Exception e) {
            LOGGER.error("[SimpleTombstone] 加载配置时发生错误", e);
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
            LOGGER.error("[SimpleTombstone] 保存配置时发生错误", e);
        }
    }

    /**
     * 创建默认配置文件
     */
    private static void saveDefaultConfig() throws IOException {
        InputStream in = SimpleTombstone.class.getClassLoader().getResourceAsStream("defaultconfigs/simpletombstone.json");
        if (in != null) {
            Files.copy(in, CONFIG_PATH);
            in.close();
        } else {
            TombstoneConfig config = new TombstoneConfig();
            config.save();
        }
    }
}

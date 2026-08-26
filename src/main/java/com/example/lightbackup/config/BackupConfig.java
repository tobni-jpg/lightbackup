package com.example.lightbackup.config;

import com.example.lightbackup.LightBackup;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BackupConfig {
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(LightBackup.MOD_ID + ".json");

	public boolean enabled = true;
	public int intervalMinutes = 30;
	public String backupDirectory = "backups";
	public int maxBackups = 10;
	public boolean announceToPlayers = true;
	public boolean autoUpload = false;
	public int compressionSleepMs = 5;
	public double rateLimitUploadMBPerSec = 10.0;
	public long rateLimitCompressionSleepNs = 0;

	private static BackupConfig instance = new BackupConfig();

	private BackupConfig() {
	}

	public static BackupConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(CONFIG_PATH)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();
			BackupConfig config = new BackupConfig();
			config.enabled = getBoolean(root, "enabled", config.enabled);
			config.intervalMinutes = getInt(root, "intervalMinutes", config.intervalMinutes);
			config.backupDirectory = getString(root, "backupDirectory", config.backupDirectory);
			config.maxBackups = getInt(root, "maxBackups", config.maxBackups);
			config.announceToPlayers = getBoolean(root, "announceToPlayers", config.announceToPlayers);
			config.autoUpload = getBoolean(root, "autoUpload", config.autoUpload);
			config.compressionSleepMs = getInt(root, "compressionSleepMs", config.compressionSleepMs);
			config.rateLimitUploadMBPerSec = getDouble(root, "rateLimitUploadMBPerSec", config.rateLimitUploadMBPerSec);
			config.rateLimitCompressionSleepNs = getLong(root, "rateLimitCompressionSleepNs", config.rateLimitCompressionSleepNs);
			instance = config;
				LightBackup.LOGGER.info("Loaded config from {}", CONFIG_PATH);
			} catch (Exception e) {
				LightBackup.LOGGER.error("Failed to load config, falling back to defaults", e);
			}
		} else {
			save();
		}
	}

	public static void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("enabled", instance.enabled);
			root.addProperty("intervalMinutes", instance.intervalMinutes);
			root.addProperty("backupDirectory", instance.backupDirectory);
			root.addProperty("maxBackups", instance.maxBackups);
			root.addProperty("announceToPlayers", instance.announceToPlayers);
			root.addProperty("autoUpload", instance.autoUpload);
			root.addProperty("compressionSleepMs", instance.compressionSleepMs);
			root.addProperty("rateLimitUploadMBPerSec", instance.rateLimitUploadMBPerSec);
			root.addProperty("rateLimitCompressionSleepNs", instance.rateLimitCompressionSleepNs);
			Files.writeString(CONFIG_PATH, new GsonBuilder().setPrettyPrinting().create().toJson(root));
		} catch (IOException e) {
			LightBackup.LOGGER.error("Failed to save config to {}", CONFIG_PATH, e);
		}
	}

	private static boolean getBoolean(JsonObject root, String key, boolean fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsBoolean() : fallback;
	}

	private static int getInt(JsonObject root, String key, int fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsInt() : fallback;
	}

	private static String getString(JsonObject root, String key, String fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : fallback;
	}

	private static long getLong(JsonObject root, String key, long fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsLong() : fallback;
	}

	private static double getDouble(JsonObject root, String key, double fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsDouble() : fallback;
	}
}

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

	// Configurable messages. Placeholders: {reason} {filename} {error} {summary}
	public String msgBackupCreate = "[LightBackup] Creating {reason} backup '{filename}'...";
	public String msgBackupDone = "[LightBackup] Backup '{filename}' finished.";
	public String msgBackupRunning = "[LightBackup] A backup is already running, please wait.";
	public String msgSaveFailed = "[LightBackup] Backup aborted: could not save the world.";
	public String msgBackupFailed = "[LightBackup] Backup failed: {error}";
	public String msgUploadStart = "[LightBackup] Uploading '{filename}' to Google Drive...";
	public String msgUploadDone = "[LightBackup] Upload of '{filename}' finished ({summary}).";
	public String msgUploadFailed = "[LightBackup] Upload failed: {error}";
	public String msgNoBackups = "[LightBackup] No backups found to upload.";

	private static BackupConfig instance = new BackupConfig();

	private BackupConfig() {
	}

	public static BackupConfig get() {
		return instance;
	}

	/**
	 * Replaces {@code {placeholder}} occurrences in a message template with the given values.
	 * Arguments are key/value pairs: {@code format(tpl, "filename", f, "reason", r)}.
	 */
	public static String fmt(String template, String... keyValuePairs) {
		String s = template == null ? "" : template;
		for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
			String value = keyValuePairs[i + 1];
			s = s.replace("{" + keyValuePairs[i] + "}", value == null ? "" : value);
		}
		return s;
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
				config.msgBackupCreate = getString(root, "msgBackupCreate", config.msgBackupCreate);
				config.msgBackupDone = getString(root, "msgBackupDone", config.msgBackupDone);
				config.msgBackupRunning = getString(root, "msgBackupRunning", config.msgBackupRunning);
				config.msgSaveFailed = getString(root, "msgSaveFailed", config.msgSaveFailed);
				config.msgBackupFailed = getString(root, "msgBackupFailed", config.msgBackupFailed);
				config.msgUploadStart = getString(root, "msgUploadStart", config.msgUploadStart);
				config.msgUploadDone = getString(root, "msgUploadDone", config.msgUploadDone);
				config.msgUploadFailed = getString(root, "msgUploadFailed", config.msgUploadFailed);
				config.msgNoBackups = getString(root, "msgNoBackups", config.msgNoBackups);
				instance = config;
				LightBackup.LOGGER.info("Loaded config from {}", CONFIG_PATH);
				save();
			} catch (Exception e) {
				LightBackup.LOGGER.error("Failed to load config, falling back to defaults", e);
			}
		} else {
			save();
		}
	}

	/** Writes every setting (including defaults for missing keys) back to disk. */
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
			root.addProperty("msgBackupCreate", instance.msgBackupCreate);
			root.addProperty("msgBackupDone", instance.msgBackupDone);
			root.addProperty("msgBackupRunning", instance.msgBackupRunning);
			root.addProperty("msgSaveFailed", instance.msgSaveFailed);
			root.addProperty("msgBackupFailed", instance.msgBackupFailed);
			root.addProperty("msgUploadStart", instance.msgUploadStart);
			root.addProperty("msgUploadDone", instance.msgUploadDone);
			root.addProperty("msgUploadFailed", instance.msgUploadFailed);
			root.addProperty("msgNoBackups", instance.msgNoBackups);
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

	private static long getLong(JsonObject root, String key, long fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsLong() : fallback;
	}

	private static double getDouble(JsonObject root, String key, double fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsDouble() : fallback;
	}

	private static String getString(JsonObject root, String key, String fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : fallback;
	}
}

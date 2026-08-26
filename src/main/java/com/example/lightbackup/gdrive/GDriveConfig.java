package com.example.lightbackup.gdrive;

import com.example.lightbackup.LightBackup;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GDriveConfig {
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(LightBackup.MOD_ID + "-gdrive.json");

	public String clientId = "";
	public String clientSecret = "";
	public String refreshToken = "";
	public String folderId = "";
	public boolean enabled = false;

	private static GDriveConfig instance = new GDriveConfig();

	private GDriveConfig() {
	}

	public static GDriveConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(CONFIG_PATH)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();
				GDriveConfig config = new GDriveConfig();
				config.clientId = getString(root, "clientId", "");
				config.clientSecret = getString(root, "clientSecret", "");
				config.refreshToken = getString(root, "refreshToken", "");
				config.folderId = getString(root, "folderId", "");
				config.enabled = getBoolean(root, "enabled", false) && !config.refreshToken.isEmpty();
				instance = config;
				LightBackup.LOGGER.info("Loaded Google Drive config from {}", CONFIG_PATH);
			} catch (Exception e) {
				LightBackup.LOGGER.error("Failed to load Google Drive config", e);
			}
		}
	}

	public static void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("clientId", instance.clientId);
			root.addProperty("clientSecret", instance.clientSecret);
			root.addProperty("refreshToken", instance.refreshToken);
			root.addProperty("folderId", instance.folderId);
			root.addProperty("enabled", instance.enabled);
			Files.writeString(CONFIG_PATH, new GsonBuilder().setPrettyPrinting().create().toJson(root));
		} catch (IOException e) {
			LightBackup.LOGGER.error("Failed to save Google Drive config to {}", CONFIG_PATH, e);
		}
	}

	public boolean isConfigured() {
		return !clientId.isEmpty() && !clientSecret.isEmpty() && !refreshToken.isEmpty();
	}

	public static void configure(String clientId, String clientSecret, String folderId) {
		instance.clientId = clientId;
		instance.clientSecret = clientSecret;
		instance.folderId = folderId;
		instance.refreshToken = "";
		instance.enabled = false;
		save();
	}

	public static void setRefreshToken(String token) {
		instance.refreshToken = token;
		instance.enabled = true;
		save();
	}

	private static boolean getBoolean(JsonObject root, String key, boolean fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsBoolean() : fallback;
	}

	private static String getString(JsonObject root, String key, String fallback) {
		return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : fallback;
	}
}

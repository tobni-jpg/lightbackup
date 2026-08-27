package com.example.lightbackup;

import com.example.lightbackup.backup.BackupManager;
import com.example.lightbackup.config.BackupConfig;
import com.example.lightbackup.gdrive.GDriveConfig;
import com.example.lightbackup.gdrive.GDriveUploader;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LightBackup implements ModInitializer {
	public static final String MOD_ID = "lightbackup";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		BackupConfig.load();
		GDriveConfig.load();

		ServerLifecycleEvents.SERVER_STARTED.register(BackupManager::onServerStart);

		ServerTickEvents.END_SERVER_TICK.register(BackupManager::tick);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("backup")
					.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
					.executes(context -> {
						BackupManager.startBackup(context.getSource().getServer(), "manual");
						return 1;
					})
					.then(Commands.literal("now")
							.executes(context -> {
								BackupManager.startBackup(context.getSource().getServer(), "manual");
								return 1;
							}))
					.then(Commands.literal("list")
							.executes(context -> {
								BackupManager.sendBackupList(context.getSource());
								return 1;
							}))
					.then(Commands.literal("reload")
							.executes(context -> {
								BackupConfig.load();
								GDriveConfig.load();
								context.getSource().sendSuccess(() -> Component.literal("[LightBackup] Config reloaded."), true);
								return 1;
							}))
					.then(Commands.literal("gdrive-setup")
							.then(Commands.argument("client_id", StringArgumentType.word())
									.then(Commands.argument("client_secret", StringArgumentType.word())
											.executes(context -> {
												String clientId = StringArgumentType.getString(context, "client_id");
												String clientSecret = StringArgumentType.getString(context, "client_secret");
												GDriveConfig.configure(clientId, clientSecret, "");
												String url = GDriveUploader.getAuthUrl(clientId);
												context.getSource().sendSuccess(() -> Component.literal("[LightBackup] Google Drive credentials saved. Auth URL:"), false);
												context.getSource().sendSuccess(() -> Component.literal(url), false);
												context.getSource().sendSuccess(() -> Component.literal("[LightBackup] Open the URL, authorize, copy the code, then run /backup gdrive-auth <code>"), false);
												return 1;
											}))))
					.then(Commands.literal("gdrive-auth")
							.then(Commands.argument("code", StringArgumentType.greedyString())
									.executes(context -> {
										String code = StringArgumentType.getString(context, "code");
										GDriveConfig gconfig = GDriveConfig.get();
										try {
											String token = GDriveUploader.exchangeCodeForToken(gconfig.clientId, gconfig.clientSecret, code);
											GDriveConfig.setRefreshToken(token);
											context.getSource().sendSuccess(() -> Component.literal("[LightBackup] Google Drive authenticated successfully!"), true);
										} catch (Exception e) {
											LightBackup.LOGGER.error("Google Drive auth failed", e);
											context.getSource().sendSuccess(() -> Component.literal("[LightBackup] Auth failed: " + e.getMessage()), false);
										}
										return 1;
									})))
					.then(Commands.literal("gdrive-upload")
							.executes(context -> {
								context.getSource().sendSuccess(() -> Component.literal("[LightBackup] Uploading latest backup to Google Drive..."), false);
								Thread.startVirtualThread(() -> {
									try {
										Path backupDir = Path.of(BackupConfig.get().backupDirectory).toAbsolutePath().normalize();
										List<Path> backups = new ArrayList<>();
										try (var stream = Files.list(backupDir)) {
											stream.filter(p -> p.getFileName().toString().startsWith("backup-") && p.getFileName().toString().endsWith(".zip"))
													.sorted(Comparator.comparing(p -> p.getFileName().toString()))
													.forEach(backups::add);
										}
										if (backups.isEmpty()) {
											context.getSource().sendSuccess(() -> Component.literal("[LightBackup] No backups found to upload."), false);
											return;
										}
										GDriveUploader.uploadFile(backups.get(backups.size() - 1).toAbsolutePath().toString());
										context.getSource().sendSuccess(() -> Component.literal("[LightBackup] Upload finished."), false);
									} catch (Exception e) {
										LightBackup.LOGGER.error("Manual upload failed", e);
										context.getSource().sendSuccess(() -> Component.literal("[LightBackup] Upload failed: " + e.getMessage()), false);
									}
								});
								return 1;
							})));
		});
	}
}

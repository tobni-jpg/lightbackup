package com.example.lightbackup.backup;

import com.example.lightbackup.LightBackup;
import com.example.lightbackup.config.BackupConfig;
import com.example.lightbackup.gdrive.GDriveConfig;
import com.example.lightbackup.gdrive.GDriveUploader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BackupManager {
	private static final DateTimeFormatter FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final String PREFIX = "backup-";
	private static final String SUFFIX = ".zip";

	private static final AtomicBoolean running = new AtomicBoolean(false);
	private static long lastBackupTick = 0;

	private BackupManager() {
	}

	public static void onServerStart(MinecraftServer server) {
		lastBackupTick = server.getTickCount();
		BackupConfig config = BackupConfig.get();
		LightBackup.LOGGER.info("Light Backup enabled: backup every {} minute(s) to '{}', keeping {} backup(s)",
				config.intervalMinutes, config.backupDirectory, config.maxBackups);
	}

	public static void tick(MinecraftServer server) {
		BackupConfig config = BackupConfig.get();
		if (!config.enabled) {
			return;
		}
		long intervalTicks = config.intervalMinutes * 60L * 20L;
		if (intervalTicks <= 0 || running.get()) {
			return;
		}
		if (server.getTickCount() - lastBackupTick >= intervalTicks) {
			lastBackupTick = server.getTickCount();
			startBackup(server, "scheduled");
		}
	}

	public static void startBackup(MinecraftServer server, String reason) {
		if (!running.compareAndSet(false, true)) {
			broadcast(server, accent("[LightBackup] A backup is already running, please wait."));
			return;
		}

		try {
			// Force the world to be written to disk before zipping it.
			server.executeBlocking(() -> server.saveEverything(false, true, true));
		} catch (Exception e) {
			LightBackup.LOGGER.error("Failed to save the world before backup", e);
			running.set(false);
			broadcast(server, error("[LightBackup] Backup aborted: could not save the world."));
			return;
		}

		Thread thread = new Thread(() -> {
			try {
				createBackup(server, reason);
			} catch (Exception e) {
				LightBackup.LOGGER.error("Backup failed", e);
				broadcast(server, error("[LightBackup] Backup failed: " + e.getMessage()));
			} finally {
				running.set(false);
			}
		}, "LightBackup-ZipThread");
		thread.setDaemon(true);
		thread.start();
	}

	private static void createBackup(MinecraftServer server, String reason) throws IOException {
		BackupConfig config = BackupConfig.get();
		Path worldDir = getWorldDir(server);
		Path backupDir = getBackupDir();
		Files.createDirectories(backupDir);

		String filename = PREFIX + LocalDateTime.now().format(FILENAME_FORMATTER) + SUFFIX;
		Path zipFile = backupDir.resolve(filename);

		broadcast(server, accent("[LightBackup] Creating " + reason + " backup '" + filename + "'..."));
		zipDirectory(worldDir, zipFile, backupDir.toAbsolutePath().normalize());
		pruneBackups(backupDir, config.maxBackups);

		LightBackup.LOGGER.info("Backup '{}' created ({})", filename, worldDir);
		broadcast(server, ok("[LightBackup] Backup '" + filename + "' finished."));

		if (config.autoUpload && GDriveConfig.get().isConfigured()) {
			broadcast(server, accent("[LightBackup] Uploading '" + filename + "' to Google Drive..."));
			try {
				GDriveUploader.uploadFile(zipFile.toAbsolutePath().toString());
				broadcast(server, ok("[LightBackup] Upload '" + filename + "' finished."));
			} catch (Exception e) {
				LightBackup.LOGGER.error("Google Drive upload failed", e);
				broadcast(server, error("[LightBackup] Upload failed: " + e.getMessage()));
			}
		}
	}

	public static void sendBackupList(CommandSourceStack source) {
		Path backupDir = getBackupDir();
		List<Path> backups = listBackups(backupDir);
		source.sendSuccess(() -> Component.literal("[LightBackup] " + backups.size() + " backup(s) in '" + backupDir + "':"), false);
		for (Path backup : backups) {
			Path b = backup;
			source.sendSuccess(() -> Component.literal(" - " + b.getFileName()), false);
		}
	}

	private static Path getWorldDir(MinecraftServer server) throws IOException {
		return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
	}

	private static Path getBackupDir() {
		return Path.of(BackupConfig.get().backupDirectory).toAbsolutePath().normalize();
	}

	private static List<Path> listBackups(Path backupDir) {
		if (!Files.isDirectory(backupDir)) {
			return List.of();
		}
		try (var stream = Files.list(backupDir)) {
			return stream
					.filter(path -> path.getFileName().toString().startsWith(PREFIX) && path.getFileName().toString().endsWith(SUFFIX))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			LightBackup.LOGGER.warn("Failed to list backups in {}", backupDir, e);
			return List.of();
		}
	}

	private static void pruneBackups(Path backupDir, int maxBackups) throws IOException {
		if (maxBackups <= 0) {
			return;
		}
		List<Path> backups = listBackups(backupDir);
		int toDelete = backups.size() - maxBackups;
		for (int i = 0; i < toDelete; i++) {
			Files.deleteIfExists(backups.get(i));
			LightBackup.LOGGER.info("Deleted old backup '{}'", backups.get(i).getFileName());
		}
	}

	private static void zipDirectory(Path sourceDir, Path zipFile, Path excludedDir) throws IOException {
		BackupConfig config = BackupConfig.get();
		try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(zipFile));
			 ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {

			Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					if (dir.toAbsolutePath().normalize().startsWith(excludedDir)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					String entry = toZipName(sourceDir, dir);
					if (!entry.isEmpty()) {
						zipOut.putNextEntry(new ZipEntry(entry + "/"));
						zipOut.closeEntry();
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (file.getFileName().toString().equals("session.lock")) {
						return FileVisitResult.CONTINUE;
					}
					zipOut.putNextEntry(new ZipEntry(toZipName(sourceDir, file)));
					try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
						byte[] buf = new byte[8192];
						int read;
						while ((read = in.read(buf)) != -1) {
							zipOut.write(buf, 0, read);
							if (config.compressionSleepMs > 0) {
								try {
									Thread.sleep(config.compressionSleepMs);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									throw new IOException("Compression interrupted", e);
								}
							}
						}
					}
					zipOut.closeEntry();
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private static String toZipName(Path sourceDir, Path path) {
		return sourceDir.relativize(path).toString().replace('\\', '/');
	}

	private static void broadcast(MinecraftServer server, Component message) {
		LightBackup.LOGGER.info(message.getString());
		if (BackupConfig.get().announceToPlayers) {
			server.execute(() -> server.getPlayerList().broadcastSystemMessage(message, false));
		}
	}

	private static Component ok(String text) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55)));
	}

	private static Component error(String text) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555)));
	}

	private static Component accent(String text) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF)));
	}
}

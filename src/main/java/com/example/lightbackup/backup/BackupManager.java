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

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.compress.archivers.zip.DefaultBackingStoreSupplier;
import org.apache.commons.compress.archivers.zip.ParallelScatterZipCreator;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.parallel.InputStreamSupplier;
import org.apache.commons.compress.parallel.ScatterGatherBackingStoreSupplier;

public final class BackupManager {
	private static final DateTimeFormatter FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final String PREFIX = "backup-";
	private static final String SUFFIX = ".zip";

	private static final AtomicBoolean running = new AtomicBoolean(false);
	// wall-clock based scheduling: 26.2 pauses the tick loop when the server is
	// empty, so tick-counters never advance while nobody is online
	private static long lastBackupWallClock = 0;
	private static Path lastBackupFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("lightbackup.lastbackup");
	}

	private BackupManager() {
	}

	public static void onServerStart(MinecraftServer server) {
		try {
			if (Files.exists(lastBackupFile())) {
				lastBackupWallClock = Long.parseLong(Files.readString(lastBackupFile()).trim());
			}
		} catch (Exception e) {
			LightBackup.LOGGER.warn("Failed to read last-backup timestamp, scheduling from now", e);
			lastBackupWallClock = System.currentTimeMillis();
		}
		if (lastBackupWallClock <= 0) {
			lastBackupWallClock = System.currentTimeMillis();
		}
		BackupConfig config = BackupConfig.get();
		LightBackup.LOGGER.info("Light Backup enabled: backup every {} minute(s) to '{}', keeping {} backup(s)",
				config.intervalMinutes, config.backupDirectory, config.maxBackups);
	}

	public static void tick(MinecraftServer server) {
		BackupConfig config = BackupConfig.get();
		if (!config.enabled) {
			return;
		}
		long intervalMs = config.intervalMinutes * 60L * 1000L;
		if (intervalMs <= 0 || running.get()) {
			return;
		}
		if (System.currentTimeMillis() - lastBackupWallClock >= intervalMs) {
			lastBackupWallClock = System.currentTimeMillis();
			storeLastBackup();
			startBackup(server, "scheduled");
		}
	}

	private static void storeLastBackup() {
		try {
			Files.writeString(lastBackupFile(), Long.toString(lastBackupWallClock));
		} catch (IOException e) {
			LightBackup.LOGGER.warn("Failed to persist last-backup timestamp", e);
		}
	}

	public static void startBackup(MinecraftServer server, String reason) {
		BackupConfig config = BackupConfig.get();
		if (!running.compareAndSet(false, true)) {
			broadcast(server, accent(config.msgBackupRunning));
			return;
		}

		try {
			// Force the world to be written to disk before zipping it.
			server.executeBlocking(() -> server.saveEverything(false, true, true));
		} catch (Exception e) {
			LightBackup.LOGGER.error("Failed to save the world before backup", e);
			running.set(false);
			broadcast(server, error(config.msgSaveFailed));
			return;
		}

		Thread thread = new Thread(() -> {
			try {
				createBackup(server, reason);
			} catch (Exception e) {
				LightBackup.LOGGER.error("Backup failed", e);
				broadcast(server, error(BackupConfig.fmt(config.msgBackupFailed, "error", e.getMessage())));
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

		broadcast(server, accent(BackupConfig.fmt(config.msgBackupCreate, "reason", reason, "filename", filename)));
		zipDirectory(worldDir, zipFile, backupDir.toAbsolutePath().normalize());
		pruneBackups(backupDir, config.maxBackups);

		LightBackup.LOGGER.info("Backup '{}' created ({})", filename, worldDir);
		broadcast(server, ok(BackupConfig.fmt(config.msgBackupDone, "filename", filename)));

		if (!config.autoUpload || !GDriveConfig.get().isConfigured()) {
			return;
		}
		int every = Math.max(0, config.uploadEveryNthBackup);
		if (every == 0) {
			LightBackup.LOGGER.info("Auto-upload disabled (uploadEveryNthBackup = 0), skipping upload of '{}'", filename);
			return;
		}
		long count = nextBackupCount();
		storeBackupCount(count);
		long done = count % every;
		if (done == 0) {
			broadcast(server, accent(BackupConfig.fmt(config.msgUploadStart, "filename", filename)));
			try {
				String summary = GDriveUploader.uploadFile(zipFile.toAbsolutePath().toString());
				broadcast(server, ok(BackupConfig.fmt(config.msgUploadDone, "filename", filename, "summary", summary)));
			} catch (Exception e) {
				LightBackup.LOGGER.error("Google Drive upload failed", e);
				broadcast(server, error(BackupConfig.fmt(config.msgUploadFailed, "error", e.getMessage())));
			}
		} else {
			long remaining = every - done;
			broadcast(server, accent(BackupConfig.fmt(config.msgUploadSkipped,
					"done", String.valueOf(done),
					"every", String.valueOf(every),
					"remaining", String.valueOf(remaining))));
		}
	}

	private static Path counterFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("lightbackup.count");
	}

	private static long nextBackupCount() {
		try {
			if (Files.exists(counterFile())) {
				return Long.parseLong(Files.readString(counterFile()).trim()) + 1;
			}
		} catch (Exception e) {
			LightBackup.LOGGER.warn("Failed to read backup counter, restarting at 1", e);
		}
		return 1;
	}

	private static void storeBackupCount(long count) {
		try {
			Files.writeString(counterFile(), Long.toString(count));
		} catch (IOException e) {
			LightBackup.LOGGER.warn("Failed to persist backup counter", e);
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

	/**
	 * Zips the world directory. Compression runs on {@code compressionThreads} parallel
	 * workers (bounded by the available cores); each worker can be CPU-throttled with
	 * {@code compressionSleepMs} and uses deflater level {@code compressionLevel}.
	 */
	private static void zipDirectory(Path sourceDir, Path zipFile, Path excludedDir) throws IOException {
		BackupConfig config = BackupConfig.get();

		List<Path> files = new ArrayList<>();
		Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				return dir.toAbsolutePath().normalize().startsWith(excludedDir)
						? FileVisitResult.SKIP_SUBTREE
						: FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (!file.getFileName().toString().equals("session.lock")) {
					files.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});

		int threads = Math.max(1, Math.min(config.compressionThreads, Runtime.getRuntime().availableProcessors()));
		ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "LightBackup-Compress");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});

		// buffer compressed entries in temp files instead of the heap. The
		// container's /tmp is often a tiny tmpfs, so use the backup directory
		// (real disk, excluded from the zip) for the scatter files
		Path scatterDir = excludedDir.resolve(".scatter-tmp");
		Files.createDirectories(scatterDir);
		ScatterGatherBackingStoreSupplier backingStore = new DefaultBackingStoreSupplier(scatterDir);

		try (ZipArchiveOutputStream zipOut = new ZipArchiveOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
			zipOut.setLevel(Math.max(1, Math.min(9, config.compressionLevel)));
			ParallelScatterZipCreator scatter = new ParallelScatterZipCreator(pool, backingStore);

			long sleepMs = Math.max(0, config.compressionSleepMs);
			for (Path file : files) {
				ZipArchiveEntry entry = new ZipArchiveEntry(file.toFile(), toZipName(sourceDir, file));
				// must be set explicitly - entries without a method abort the scatter write
				entry.setMethod(ZipArchiveEntry.DEFLATED);
				final long sleep = sleepMs;
				InputStreamSupplier supplier;
				if (sleep == 0) {
					supplier = () -> openUnchecked(file);
				} else {
					supplier = () -> new SleepingInputStream(openUnchecked(file), sleep);
				}
				scatter.addArchiveEntry(entry, supplier);
			}
			try {
				scatter.writeTo(zipOut);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Compression interrupted", e);
			} catch (ExecutionException e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				throw new IOException("Compression failed: " + cause.getMessage(), cause);
			}
		} finally {
			pool.shutdownNow();
			// backing stores are closed by writeTo; clean up the temp directory
			try (var walk = Files.walk(scatterDir)) {
				walk.sorted(Comparator.reverseOrder()).forEach(p -> {
					try {
						Files.deleteIfExists(p);
					} catch (IOException ignored) {
					}
				});
			} catch (IOException ignored) {
			}
		}
	}

	/** Opens a file stream, wrapping the checked {@link IOException} for use inside {@link InputStreamSupplier}. */
	private static InputStream openUnchecked(Path file) {
		try {
			return new BufferedInputStream(Files.newInputStream(file));
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** Pauses {@code sleepMs} after every 8KB read - keeps CPU load per worker in check. */
	private static final class SleepingInputStream extends FilterInputStream {
		private final long sleepMs;

		SleepingInputStream(InputStream in, long sleepMs) {
			super(in);
			this.sleepMs = sleepMs;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int n = super.read(b, off, len);
			if (n > 0 && sleepMs > 0) {
				try {
					Thread.sleep(sleepMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted during compression", e);
				}
			}
			return n;
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

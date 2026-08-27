package com.example.lightbackup.gdrive;

import com.example.lightbackup.LightBackup;
import com.example.lightbackup.config.BackupConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public final class GDriveUploader {
	private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
	private static final String UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files";
	private static final String UPLOAD_SCOPE = "https://www.googleapis.com/auth/drive.file";
	private static final String AUTH_URL_TEMPLATE = "https://accounts.google.com/o/oauth2/v2/auth"
			+ "?client_id=%s&redirect_uri=%s&scope=%s&response_type=code&access_type=offline&prompt=consent";
	private static final String REDIRECT_URI = "http://localhost";

	private GDriveUploader() {
	}

	public static String getAuthUrl(String clientId) {
		return AUTH_URL_TEMPLATE.formatted(
				urlEncode(clientId),
				urlEncode(REDIRECT_URI),
				urlEncode(UPLOAD_SCOPE));
	}

	public static String exchangeCodeForToken(String clientId, String clientSecret, String code) throws IOException {
		String body = "client_id=" + urlEncode(clientId)
				+ "&client_secret=" + urlEncode(clientSecret)
				+ "&code=" + urlEncode(code)
				+ "&grant_type=authorization_code"
				+ "&redirect_uri=" + urlEncode(REDIRECT_URI);

		String response = postForm(TOKEN_URL, body);
		return extractJsonString(response, "access_token");
	}

	/**
	 * Uploads the given file to Google Drive and returns a summary like
	 * {@code "4.68 MB in 4.8s (997 KB/s)"} for display in messages.
	 */
	public static String uploadFile(String filePath) throws IOException {
		GDriveConfig gconfig = GDriveConfig.get();
		BackupConfig config = BackupConfig.get();
		if (!gconfig.isConfigured()) {
			throw new IOException("Google Drive not configured. Run /backup gdrive-setup first.");
		}

		String accessToken = getAccessToken(gconfig);
		Path file = Path.of(filePath);
		long fileSize = Files.size(file);
		String fileName = file.getFileName().toString();

		String metadata;
		if (gconfig.folderId.isEmpty()) {
			metadata = "{\"name\":\"%s\",\"mimeType\":\"application/zip\"}".formatted(fileName);
		} else {
			metadata = "{\"name\":\"%s\",\"mimeType\":\"application/zip\",\"parents\":[\"%s\"]}".formatted(fileName, gconfig.folderId);
		}

		Instant start = Instant.now();
		long[] uploaded = {0};

		long bytesPerSec = bytesPerSec(config);

		if (fileSize < 5 * 1024 * 1024) {
			simpleUpload(accessToken, metadata, file, fileSize, bytesPerSec, uploaded);
		} else {
			resumableUpload(accessToken, metadata, file, fileSize, bytesPerSec, uploaded);
		}

		Duration elapsed = Duration.between(start, Instant.now());
		double secs = Math.max(0.05, elapsed.toMillis() / 1000.0);
		return "%.2f MB in %.1fs (%.0f KB/s)".formatted(
				fileSize / (1024.0 * 1024.0), secs, fileSize / 1024.0 / secs);
	}

	private static long bytesPerSec(BackupConfig config) {
		return (long) (config.rateLimitUploadMBPerSec * 1024 * 1024);
	}

	private static void simpleUpload(String token, String metadata, Path file, long fileSize,
			long bytesPerSec, long[] uploaded) throws IOException {
		String boundary = "LightBackupBoundary" + System.nanoTime();
		String contentType = "multipart/related; boundary=" + boundary;

		try (OutputStream conn = openConnection(UPLOAD_URL + "?uploadType=multipart", "POST", contentType, token)) {
			StringBuilder header = new StringBuilder();
			header.append("--").append(boundary).append("\r\n");
			header.append("Content-Type: application/json; charset=UTF-8\r\n\r\n");
			header.append(metadata).append("\r\n");
			conn.write(header.toString().getBytes(StandardCharsets.UTF_8));

			StringBuilder fileHeader = new StringBuilder();
			fileHeader.append("--").append(boundary).append("\r\n");
			fileHeader.append("Content-Type: application/zip\r\n\r\n");
			conn.write(fileHeader.toString().getBytes(StandardCharsets.UTF_8));

			try (InputStream in = new ThrottledInputStream(Files.newInputStream(file), bytesPerSec)) {
				byte[] buf = new byte[8192];
				int read;
				while ((read = in.read(buf)) != -1) {
					conn.write(buf, 0, read);
					uploaded[0] += read;
				}
			}

			StringBuilder footer = new StringBuilder();
			footer.append("\r\n--").append(boundary).append("--\r\n");
			conn.write(footer.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new IOException("Simple upload failed: " + e.getMessage(), e);
		}
	}

	private static void resumableUpload(String token, String metadata, Path file, long fileSize,
			long bytesPerSec, long[] uploaded) throws IOException {
		String initContentType = "application/json; charset=UTF-8";
		String initUrl = UPLOAD_URL + "?uploadType=resumable&uploadSize=" + fileSize;
		HttpURLConnection initConn = (HttpURLConnection) URI.create(initUrl).toURL().openConnection();
		initConn.setRequestMethod("POST");
		initConn.setRequestProperty("Authorization", "Bearer " + token);
		initConn.setRequestProperty("Content-Type", initContentType);
		initConn.setDoOutput(true);

		try (OutputStream out = initConn.getOutputStream()) {
			out.write(metadata.getBytes(StandardCharsets.UTF_8));
		}

		String sessionUrl = initConn.getHeaderField("Location");
		if (sessionUrl == null || sessionUrl.isEmpty()) {
			throw new IOException("Failed to get resumable upload session URL");
		}

		HttpURLConnection uploadConn = (HttpURLConnection) URI.create(sessionUrl).toURL().openConnection();
		uploadConn.setRequestMethod("PUT");
		uploadConn.setRequestProperty("Authorization", "Bearer " + token);
		uploadConn.setRequestProperty("Content-Length", String.valueOf(fileSize));
		uploadConn.setDoOutput(true);
		uploadConn.setFixedLengthStreamingMode(fileSize);

		try (OutputStream out = uploadConn.getOutputStream();
			 InputStream in = new ThrottledInputStream(Files.newInputStream(file), bytesPerSec)) {
			byte[] buf = new byte[8192];
			int read;
			while ((read = in.read(buf)) != -1) {
				out.write(buf, 0, read);
				uploaded[0] += read;
			}
		}

		int code = uploadConn.getResponseCode();
		if (code < 200 || code >= 300) {
			String err = new String(uploadConn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			throw new IOException("Resumable upload failed (HTTP " + code + "): " + err);
		}
	}

	private static String getAccessToken(GDriveConfig gconfig) throws IOException {
		String body = "client_id=" + urlEncode(gconfig.clientId)
				+ "&client_secret=" + urlEncode(gconfig.clientSecret)
				+ "&refresh_token=" + urlEncode(gconfig.refreshToken)
				+ "&grant_type=refresh_token";

		String response = postForm(TOKEN_URL, body);
		String token = extractJsonString(response, "access_token");
		if (token == null || token.isEmpty()) {
			throw new IOException("Failed to get access token from refresh token");
		}
		return token;
	}

	private static OutputStream openConnection(String urlStr, String method, String contentType, String token)
			throws IOException {
		HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
		conn.setRequestMethod(method);
		conn.setRequestProperty("Authorization", "Bearer " + token);
		conn.setRequestProperty("Content-Type", contentType);
		conn.setDoOutput(true);
		return conn.getOutputStream();
	}

	private static String postForm(String urlStr, String formBody) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		conn.setDoOutput(true);

		try (OutputStream out = conn.getOutputStream()) {
			out.write(formBody.getBytes(StandardCharsets.UTF_8));
		}

		int code = conn.getResponseCode();
		String responseBody = code >= 200 && code < 300
				? new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
				: new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

		if (code < 200 || code >= 300) {
			throw new IOException("HTTP " + code + " from token endpoint: " + responseBody);
		}
		return responseBody;
	}

	private static String extractJsonString(String json, String key) {
		String search = "\"" + key + "\":";
		int start = json.indexOf(search);
		if (start == -1) {
			return null;
		}
		start += search.length();
		while (start < json.length() && json.charAt(start) == ' ') {
			start++;
		}
		if (start >= json.length()) {
			return null;
		}
		if (json.charAt(start) == '"') {
			start++;
			int end = json.indexOf('"', start);
			return end > start ? json.substring(start, end) : null;
		}
		int end = start;
		while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
			end++;
		}
		return json.substring(start, end).trim();
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}

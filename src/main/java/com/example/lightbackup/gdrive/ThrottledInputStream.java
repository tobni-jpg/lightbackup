package com.example.lightbackup.gdrive;

import java.io.IOException;
import java.io.InputStream;

public final class ThrottledInputStream extends InputStream {
	private final InputStream delegate;
	private final long bytesPerSecond;
	private long windowStart;
	private long windowBytes;

	public ThrottledInputStream(InputStream delegate, long bytesPerSecond) {
		this.delegate = delegate;
		this.bytesPerSecond = bytesPerSecond;
		this.windowStart = System.nanoTime();
		this.windowBytes = 0;
	}

	@Override
	public int read() throws IOException {
		throttle();
		return delegate.read();
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		throttle();
		int read = delegate.read(b, off, len);
		if (read > 0) {
			windowBytes += read;
		}
		return read;
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}

	private void throttle() {
		if (bytesPerSecond <= 0) {
			return;
		}
		long now = System.nanoTime();
		long elapsed = now - windowStart;

		if (elapsed >= 1_000_000_000L) {
			windowStart = now;
			windowBytes = 0;
			return;
		}

		if (windowBytes >= bytesPerSecond) {
			long sleepMs = (1_000_000_000L - elapsed) / 1_000_000L;
			if (sleepMs > 0) {
				try {
					Thread.sleep(sleepMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			windowStart = System.nanoTime();
			windowBytes = 0;
		}
	}
}

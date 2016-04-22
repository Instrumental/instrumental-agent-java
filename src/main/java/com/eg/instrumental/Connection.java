package com.eg.instrumental;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public final class Connection implements Runnable {

	private static final Charset ASCII = Charset.forName("ASCII");
	private static final ThreadFactory connectionThreadFactory = new ConnectionThreadFactory();

	private AgentOptions agentOptions;
    public static final int MAX_QUEUE_SIZE = 5000;
	LinkedBlockingDeque<String> messages = new LinkedBlockingDeque<String>(MAX_QUEUE_SIZE);
	private Thread worker = null;
	private Socket socket = null;
	OutputStream outputStream = null;

	private final ReentrantLock streamLock = new ReentrantLock();

	private int errors = 0;
	private boolean queueFullWarned = false;
	private boolean shutdown = false;
	int bytesWritten = 0;

	private long maxReconnectDelay = 15000;
	private long reconnectBackoff = 2;


	private static final Logger LOG = Logger.getLogger(Connection.class.getName());

	public Connection(final AgentOptions agentOptions) {
		this.agentOptions = agentOptions;
	}

	public AgentOptions getAgentOptions() {
		return agentOptions;
	}

	void send(String command, boolean synchronous) {
		if (worker == null || !worker.isAlive()) {
			worker = connectionThreadFactory.newThread(this);
			worker.start();
		}

		if (synchronous) {
			boolean success = false;
			do {
				streamLock.lock();
				try {
					ensureStream();
					write(command, true);
					success = true;
				} catch (IOException ioe) {
					cleanupStream();
					success = false;
					try {
						backoffReconnect();
					} catch (InterruptedException ie) {
						LOG.warning("Interrupted while attempting to send synchronous message.");
						break;
					}
				} catch (IllegalArgumentException iae) {
					LOG.severe(iae.toString());
					send(new Metric(Metric.Type.INCREMENT, "agent.invalid_metric", 1, System.currentTimeMillis(), 1).toString(), false);
				} finally {
					streamLock.unlock();
				}
			} while (!success);
		} else {
			try {
				messages.add(command);
				queueFullWarned = false;
			} catch (IllegalStateException ise) {
				if (!queueFullWarned) {
					LOG.warning("Queue full. Dropping messages until space is available.");
					queueFullWarned = true;
				}
			}
		}
	}

	@Override
	public void run() {
		String command = null;
		while (!shutdown || !messages.isEmpty()) {
			// Make sure the socket state is kosher.
			try {
				ensureStream();
			} catch (IOException ioe) {
				cleanupStream();
			}

			// Get the next message off the queue.
			try {
				if (command == null) {
					command = messages.poll(5, TimeUnit.SECONDS);
				}

				// Try to write the message
				write(command, false);
				command = null;
			} catch (InterruptedException ie) {
				break;
			} catch (IOException ioe) {
				cleanupStream();
				try {
					backoffReconnect();
				} catch (InterruptedException ie) {
					break;
				}
			} catch (IllegalArgumentException iae) {
				// Illegally formatted command.
				LOG.severe(iae.toString());
				command = null;
				send(new Metric(Metric.Type.INCREMENT, "agent.invalid_metric", 1, System.currentTimeMillis(), 1).toString(), false);
			}
		}
	}

	private void ensureStream() throws IOException {
		streamLock.lock();
		try {
			while (outputStream == null && !shutdown) {
				socket = new Socket();
				socket.setTcpNoDelay(true);
				socket.setKeepAlive(true);
				socket.setTrafficClass(0x04 | 0x10); // Reliability, low-delay
				socket.setPerformancePreferences(0, 2, 1); // latency more important than bandwidth and connection time.
				socket.connect(new InetSocketAddress(agentOptions.getHost(), agentOptions.getPort()));
				outputStream = socket.getOutputStream();

				String hello = "hello version java/instrumental_agent/0.0.1 hostname " + getHostname() + " pid " + getProcessId("?") + " runtime " + getRuntimeInfo() + " platform " + getPlatformInfo();

				write(hello, true);
				write("authenticate " + agentOptions.getApiKey(), true);

				errors = 0;
			}
		} finally {
			streamLock.unlock();
		}
	}

	private String getHostname() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (Exception ex) {
			return "localhost";
		}
	}

	private static String getProcessId(final String fallback) {
		// Note: may fail in some JVM implementations
		// therefore fallback has to be provided

		// something like '<pid>@<hostname>', at least in SUN / Oracle JVMs
		final String jvmName = ManagementFactory.getRuntimeMXBean().getName();
		final int index = jvmName.indexOf('@');

		if (index < 1) {
			// part before '@' empty (index = 0) / '@' not found (index = -1)
			return fallback;
		}

		try {
			return Long.toString(Long.parseLong(jvmName.substring(0, index)));
		} catch (NumberFormatException e) {
			// ignore
		}
		return fallback;
	}

	private static String getPlatformInfo() {
		return System.getProperty("os.arch", "unknown").replaceAll(" ", "_") + "-" + System.getProperty("os.name", "unknown").replaceAll(" ", "_") + System.getProperty("os.version", "").replaceAll(" ", "_");
	}

	private static String getRuntimeInfo() {
		return System.getProperty("java.vendor", "java").replaceAll(" ", "_") + "/" + System.getProperty("java.version", "?").replaceAll(" ", "_");
	}


	private void cleanupStream() {
		streamLock.lock();
		if (outputStream != null) {
			try {
				outputStream.flush();
				outputStream.close();
			} catch (Exception ex) {
			} finally {
				outputStream = null;
			}
		}
		if (socket != null) {
			try {
				socket.close();
			} catch (Exception ex) {
			} finally {
				socket = null;
			}
		}
		streamLock.unlock();
	}

	public boolean isShutdown() {
		return shutdown;
	}

	public boolean isRunning() {
		return worker != null && worker.isAlive();
	}

    public boolean isQueueOverflowing() {
        return queueFullWarned;
    }

	public void setShutdown(boolean shutdown) {
		this.shutdown = shutdown;

		if (shutdown) {
			if (worker != null) {
				worker.interrupt();
				try {
					worker.join(10000);
				} catch (InterruptedException ie) {
					LOG.severe("Failed to cleanly shutdown the Connection");
				}
			}
			streamLock.lock();
			try {
				cleanupStream();
			} finally {
				streamLock.unlock();
			}
		}
	}

	private void backoffReconnect() throws InterruptedException {
		// An error occurred trying to send the current message.
		// Do a reconnect, then try to send the message again.
		// Since we've removed the message from the queue, don't discard it, and don't advance.
		errors++;
		long delay = (long) Math.min(maxReconnectDelay, Math.pow(errors++, reconnectBackoff));
		LOG.severe("Failed to connect to connection.instrumentalapp.com:8000. Retry in " + delay + "ms");
		Thread.sleep(delay);
	}

	private void write(Metric metric, boolean forceFlush) throws IOException, IllegalArgumentException {
		write(metric.toString(), forceFlush);
	}

	private void write(String message, boolean forceFlush) throws IOException {
		// If we 'shutdown', don't do anything
		if (!shutdown) {
			try {
				streamLock.lockInterruptibly();

				if (outputStream == null) {
					throw new IOException("Stream closed.");
				}

				try {
					byte[] b = (message + "\n").getBytes(ASCII);
					outputStream.write(b);

					bytesWritten += b.length;
					if (forceFlush || bytesWritten >= 1350) {
						outputStream.flush();
						bytesWritten = 0;
					}

					errors = 0;
				} finally {
					streamLock.unlock();
				}
			} catch (InterruptedException ie) {
				LOG.warning("Interrupted while acquiring send lock.");
			}
		}
	}

	/**
	 * Internal class to create the background threads.
	 */
	private static class ConnectionThreadFactory implements ThreadFactory {
		private static ThreadGroup agentThreads = new ThreadGroup("Instrumental-Agent");
		private static final AtomicInteger threadCount = new AtomicInteger(0);

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(agentThreads, r, "connection-" + threadCount.getAndIncrement());
			t.setDaemon(false);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		}
	}

}

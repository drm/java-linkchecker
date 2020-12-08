package nl.melp.linkchecker;

import org.slf4j.Logger;

public class LogMonitor {
	private final Logger logger;
	private final long startTimeMs;
	private final Status status;

	public LogMonitor(Logger logger, Status status) {
		this.startTimeMs = System.currentTimeMillis();

		this.logger = logger;
		this.status = status;
	}

	public void log() {
		try {
			long dt = (System.currentTimeMillis() - startTimeMs) / 1000;
			int size = status.numChecked();

			Runtime rt = Runtime.getRuntime();
			long memTotal = rt.totalMemory();
			long memFree = rt.freeMemory();
			long memUsed = memTotal - memFree;
			float memUsagePct = (float) memUsed / (float) (memTotal / 100);
			if (memUsagePct > 95) {
				logger.warn("Memory consumption is high. This might cause instability. Consider increasing available memory with -Xmx and -Xms flags");
			}

			logger.info(
				String.format(
					"processed: %d, processing: %d, to check: %d; (run time %ds, avg %d/s, mem usage: %d MB of %d MB (%.2f%%))",
					size,
					status.numPending(),
					status.numQueueud(),
					dt,
					size / (dt > 0 ? dt : 1),
					memUsed / 1024 / 1024,
					memTotal / 1024 / 1024,
					memUsagePct
				)
			);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

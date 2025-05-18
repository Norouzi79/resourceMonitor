package com.norouzi.resourcemonitor;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PrecisionResourceMonitor {
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    private final String processName;
    private final Set<Long> initialThreads = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> initialCpuTimes = new ConcurrentHashMap<>();
    private final AtomicLong totalCpuTimeNs = new AtomicLong();
    private final ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();

    private volatile MonitoringState state = MonitoringState.READY;
    private long startTimeNanos;
    private long initialHeapUsage;
    private int availableProcessors;

    enum MonitoringState { READY, RUNNING, STOPPED }

    public PrecisionResourceMonitor(String processName) {
        this.processName = processName;
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
        this.availableProcessors = Runtime.getRuntime().availableProcessors();
    }

    public synchronized void start() {
        validateState(MonitoringState.READY);
        state = MonitoringState.RUNNING;

        // Capture initial state
        initialThreads.addAll(getLiveThreadIds());
        initialHeapUsage = memoryBean.getHeapMemoryUsage().getUsed();
        startTimeNanos = System.nanoTime();

        // Initialize CPU times
        initialThreads.forEach(id ->
                initialCpuTimes.put(id, safeGetCpuTime(id))
        );

        // Start monitoring
        monitorExecutor.scheduleAtFixedRate(this::updateMetrics, 0, 10, TimeUnit.MILLISECONDS);
    }

    public synchronized void stopAndReport() {
        validateState(MonitoringState.RUNNING);
        state = MonitoringState.STOPPED;

        // Final data collection
        monitorExecutor.shutdownNow();
        Set<Long> finalThreads = getLiveThreadIds();
        long durationNs = System.nanoTime() - startTimeNanos;
        long finalHeapUsage = memoryBean.getHeapMemoryUsage().getUsed();

        // Calculate thread metrics
        int initialThreadCount = initialThreads.size();
        int finalThreadCount = finalThreads.size();
        int createdThreads = (int) finalThreads.stream()
                .filter(id -> !initialThreads.contains(id))
                .count();

        // Generate report
        System.out.println(generateReport(
                durationNs,
                finalHeapUsage - initialHeapUsage,
                initialThreadCount,
                finalThreadCount,
                createdThreads
        ));
    }

    private void updateMetrics() {
        getLiveThreadIds().forEach(id -> {
            long currentTime = safeGetCpuTime(id);
            Long initialTime = initialCpuTimes.putIfAbsent(id, currentTime);

            if (initialTime != null && currentTime > initialTime) {
                totalCpuTimeNs.addAndGet(currentTime - initialTime);
                initialCpuTimes.put(id, currentTime);
            }
        });
    }

    private String generateReport(long durationNs, long memoryUsedBytes,
                                  int initialThreads, int finalThreads, int createdThreads) {
        double cpuTimeMs = totalCpuTimeNs.get() / 1_000_000.0;
        double cpuUsage = (totalCpuTimeNs.get() * 100.0) / (durationNs * availableProcessors);

        return String.format(
                "\n=== Precision Resource Report: %s ===\n" +
                "CPU Time:  %12.3f ms\n" +
                "CPU Usage: %12.2f%%\n" +
                "Memory:    %12s\n" +
                "Threads - Initial: %3d, Final: %3d, Created: %3d\n" +
                "================================================",
                processName,
                cpuTimeMs,
                cpuUsage,
                formatMemory(memoryUsedBytes),
                initialThreads,
                finalThreads,
                createdThreads
        );
    }

    private String formatMemory(long bytes) {
        if (bytes == 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        double value = bytes;

        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }

        return String.format("%.2f %s (%,d bytes)", value, units[unitIndex], bytes);
    }

    private Set<Long> getLiveThreadIds() {
        return Arrays.stream(threadBean.getAllThreadIds())
                .boxed()
                .collect(Collectors.toSet());
    }

    private long safeGetCpuTime(long threadId) {
        try {
            return threadBean.getThreadCpuTime(threadId);
        } catch (Exception e) {
            return 0;
        }
    }

    private void validateState(MonitoringState expected) {
        if (state != expected) {
            throw new IllegalStateException("Monitor not in " + expected + " state");
        }
    }

    // Test Cases ==============================================

    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                testProductionWorkload();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<Void> completable = CompletableFuture.runAsync(() -> {
            try {
                testProductionWorkload();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<Void> completableFuture = CompletableFuture.runAsync(() -> {
            try {
                testProductionWorkload();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        while (!completableFuture.isDone() && !future.isDone() && !completable.isDone()) {
            continue;
        }
        System.exit(0);
    }

    private static void testProductionWorkload() throws Exception {
        PrecisionResourceMonitor monitor = new PrecisionResourceMonitor("OrderProcessing");
        monitor.start();

        ExecutorService workers = Executors.newWorkStealingPool();
        List<Future<?>> tasks = new ArrayList<>();

        // Simulate production workload
        for (int i = 0; i < 100; i++) {
            tasks.add(workers.submit(() -> {
                processOrder();
                auditOrder();
            }));
        }

        for (Future<?> task : tasks) task.get();
        workers.shutdown();
        monitor.stopAndReport();
    }

    private static void processOrder() {
        byte[] orderData = new byte[1024 * 1024]; // 1MB
        IntStream.range(0, 100_000).forEach(i -> Math.pow(i, 2));
    }

    private static void auditOrder() {
        IntStream.range(0, 50_000).forEach(i -> Math.log(i + 1));
    }
}

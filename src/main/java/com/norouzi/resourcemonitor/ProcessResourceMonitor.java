package com.norouzi.resourcemonitor;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

// The one I want
public class ProcessResourceMonitor {
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    private final String processName;
    private final Set<Long> initialThreads = new HashSet<>();
    private final Map<Long, Long> threadCpuStart = new ConcurrentHashMap<>();
    private final AtomicLong totalCpuNs = new AtomicLong();
    private final AtomicInteger createdThreads = new AtomicInteger();

    private long startTimeNanos;
    private long initialHeap;
    private int availableProcessors;
    private ScheduledExecutorService monitorService;

    public ProcessResourceMonitor(String processName) {
        this.processName = processName;
        this.availableProcessors = Runtime.getRuntime().availableProcessors();
        if (!threadBean.isThreadCpuTimeSupported()) {
            throw new UnsupportedOperationException("CPU time measurement not supported");
        }
        threadBean.setThreadCpuTimeEnabled(true);
    }

    public void start() {
        resetState();
        initialHeap = memoryBean.getHeapMemoryUsage().getUsed();
        startTimeNanos = System.nanoTime();
        monitorService = Executors.newSingleThreadScheduledExecutor();
        monitorService.scheduleAtFixedRate(this::updateThreads, 0, 10, TimeUnit.MILLISECONDS);
    }

    private void resetState() {
        initialThreads.clear();
        threadCpuStart.clear();
        totalCpuNs.set(0);
        createdThreads.set(0);
        initialThreads.addAll(getAllThreadIds());
    }

    public void stopAndReport() {
        if (monitorService != null) {
            monitorService.shutdownNow();
        }

        long durationNs = System.nanoTime() - startTimeNanos;
        long finalHeap = memoryBean.getHeapMemoryUsage().getUsed();
        Set<Long> finalThreads = getAllThreadIds();

        System.out.println(generateReport(
                durationNs,
                finalHeap - initialHeap,
                initialThreads.size(),
                finalThreads.size(),
                createdThreads.get()
        ));
    }

    private void updateThreads() {
        Set<Long> currentThreads = getAllThreadIds();
        currentThreads.stream()
                .filter(id -> !initialThreads.contains(id))
                .forEach(this::trackNewThread);
    }

    private void trackNewThread(long threadId) {
        if (threadCpuStart.putIfAbsent(threadId, threadBean.getThreadCpuTime(threadId)) == null) {
            createdThreads.incrementAndGet();
        }

        long currentCpu = threadBean.getThreadCpuTime(threadId);
        Long startCpu = threadCpuStart.get(threadId);
        if (startCpu != null && currentCpu > startCpu) {
            totalCpuNs.addAndGet(currentCpu - startCpu);
            threadCpuStart.put(threadId, currentCpu);
        }
    }

    private String generateReport(long durationNs, long memoryBytes,
                                  int initialThreadCount, int finalThreadCount, int createdCount) {
        double cpuMs = totalCpuNs.get() / 1_000_000.0;
        double cpuUsage = (totalCpuNs.get() * 100.0) / (durationNs * availableProcessors);

        return String.format(
                "\n=== Process Resource Report: %s ===\n" +
                "CPU Time:     %10.2f ms\n" +
                "CPU Usage:    %10.2f%%\n" +
                "Memory Used:  %10s\n" +
                "Threads:      %10d created (Initial: %d, Final: %d)\n" +
                "==================================================",
                processName,
                cpuMs,
                cpuUsage,
                formatMemory(memoryBytes),
                createdCount,
                initialThreadCount,
                finalThreadCount
        );
    }

    private String formatMemory(long bytes) {
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        double size = bytes;

        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format("%.2f %s", size, units[unit]);
    }

    private Set<Long> getAllThreadIds() {
        return Arrays.stream(threadBean.getAllThreadIds())
                .boxed()
                .collect(Collectors.toSet());
    }

    // Example Usage
    public static void main(String[] args) throws Exception {
        ProcessResourceMonitor monitor = new ProcessResourceMonitor("OrderProcessor");
        monitor.start();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> tasks = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            tasks.add(pool.submit(() -> {
                processOrder();
                return null;
            }));
        }

        for (Future<?> task : tasks) task.get();
        pool.shutdown();
        monitor.stopAndReport();
    }

    private static void processOrder() {
        byte[] data = new byte[1024 * 1024]; // 1MB allocation
        long sum = 0;
        for (int i = 0; i < 100_000; i++) sum += i;
    }
}

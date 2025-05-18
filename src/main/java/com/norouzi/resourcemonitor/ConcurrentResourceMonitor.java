package com.norouzi.resourcemonitor;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ConcurrentResourceMonitor {
    private static final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private static final boolean cpuTimeSupported = threadBean.isThreadCpuTimeSupported();

    private final String methodName;
    private final Set<Long> initialThreads = new HashSet<>();
    private final Map<Long, Long> threadCpuTimes = new ConcurrentHashMap<>();
    private long startMemory;
    private final long startTimeNanos;

    static {
        if (cpuTimeSupported && !threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
    }

    public ConcurrentResourceMonitor(String methodName) {
        this.methodName = methodName;
        this.startTimeNanos = System.nanoTime();
    }

    public void start() {
        // Capture initial state
        initialThreads.addAll(getAllThreadIds());
        startMemory = memoryBean.getHeapMemoryUsage().getUsed();

        // Register thread monitor
        ThreadMonitor.registerTracker(this);
    }

    public void stopAndReport() {
        // Unregister first to prevent new threads from being tracked
        ThreadMonitor.unregisterTracker(this);

        // Calculate results
        long memoryUsed = memoryBean.getHeapMemoryUsage().getUsed() - startMemory;
        long totalCpuTime = calculateTotalCpuTime();

        printReport(totalCpuTime, memoryUsed);
    }

    private long calculateTotalCpuTime() {
        return threadCpuTimes.values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    void trackThread(long threadId) {
        if (cpuTimeSupported) {
            long cpuTime = threadBean.getThreadCpuTime(threadId);
            threadCpuTimes.put(threadId, cpuTime);
        }
    }

    void updateThread(long threadId) {
        if (cpuTimeSupported && threadCpuTimes.containsKey(threadId)) {
            long currentCpuTime = threadBean.getThreadCpuTime(threadId);
            threadCpuTimes.put(threadId, currentCpuTime - threadCpuTimes.get(threadId));
        }
    }

    private void printReport(long cpuTimeNs, long memoryUsed) {
        System.out.println("\n=== Resource Report for '" + methodName + "' ===");
        System.out.printf("Active Duration: %.2f ms%n", (System.nanoTime() - startTimeNanos) / 1_000_000.0);

        if (cpuTimeSupported) {
            System.out.printf("Total CPU Time: %.3f ms%n", cpuTimeNs / 1_000_000.0);
        }

        System.out.println("Memory Used: " + memoryUsed + " bytes");
        System.out.println("Tracked Threads: " + threadCpuTimes.size());
        System.out.println("==========================================");
    }

    private static Set<Long> getAllThreadIds() {
        return Arrays.stream(threadBean.getAllThreadIds())
                .boxed()
                .collect(Collectors.toSet());
    }

    // Thread monitoring core
    private static class ThreadMonitor implements Runnable {
        private static final ScheduledExecutorService monitorExecutor =
                Executors.newSingleThreadScheduledExecutor();
        private static final Set<ConcurrentResourceMonitor> activeTrackers =
                ConcurrentHashMap.newKeySet();

        static {
            monitorExecutor.scheduleAtFixedRate(new ThreadMonitor(), 0, 1, TimeUnit.MILLISECONDS);
        }

        public static void registerTracker(ConcurrentResourceMonitor tracker) {
            activeTrackers.add(tracker);
        }

        public static void unregisterTracker(ConcurrentResourceMonitor tracker) {
            activeTrackers.remove(tracker);
        }

        @Override
        public void run() {
            Set<Long> currentThreads = getAllThreadIds();

            for (ConcurrentResourceMonitor tracker : activeTrackers) {
                currentThreads.stream()
                        .filter(threadId -> !tracker.initialThreads.contains(threadId))
                        .forEach(threadId -> {
                            if (!tracker.threadCpuTimes.containsKey(threadId)) {
                                tracker.trackThread(threadId);
                            } else {
                                tracker.updateThread(threadId);
                            }
                        });
            }
        }
    }

    // Example usage
    public static void main(String[] args) throws Exception {
        ConcurrentResourceMonitor tracker1 = new ConcurrentResourceMonitor("ServiceA");
        tracker1.start();

        ExecutorService pool1 = Executors.newFixedThreadPool(2);
        pool1.execute(() -> heavyWork(100));
        pool1.execute(() -> heavyWork(150));

        ConcurrentResourceMonitor tracker2 = new ConcurrentResourceMonitor("ServiceB");
        tracker2.start();

        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        pool2.execute(() -> lightWork(50));
        pool2.execute(() -> lightWork(75));

        pool1.shutdown();
        pool2.shutdown();
        pool1.awaitTermination(1, TimeUnit.SECONDS);
        pool2.awaitTermination(1, TimeUnit.SECONDS);

        tracker1.stopAndReport();
        tracker2.stopAndReport();
    }

    private static void heavyWork(int duration) {
        try {
            Thread.sleep(duration);
            // Simulate CPU work
            long sum = 0;
            for (int i = 0; i < 10_000_000; i++) sum += i;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void lightWork(int duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.norouzi.resourcemonitor;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

public class IsolatedResourceMonitor {
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    private final String processName;
    private final Set<Long> initialThreads = new HashSet<>();
    private final Map<Long, Long> initialCpuTimes = new ConcurrentHashMap<>();
    private long startTimeNanos;
    private long initialHeapUsage;
    private final AtomicLong totalCpuTimeNs = new AtomicLong();
    private final ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();

    public IsolatedResourceMonitor(String processName) {
        this.processName = processName;
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
    }

    public void start() {
        // Capture initial state
        initialThreads.addAll(getAllThreadIds());
        initialHeapUsage = memoryBean.getHeapMemoryUsage().getUsed();
        startTimeNanos = System.nanoTime();

        // Initialize CPU times for existing threads
        initialThreads.forEach(id ->
                initialCpuTimes.put(id, safeGetCpuTime(id))
        );

        // Start thread monitoring
        monitorExecutor.scheduleAtFixedRate(this::updateCpuTimes, 0, 10, TimeUnit.MILLISECONDS);
    }

    public void stopAndReport() {
        // Stop monitoring
        monitorExecutor.shutdownNow();

        // Calculate final metrics
        long durationNs = System.nanoTime() - startTimeNanos;
        long finalHeapUsage = memoryBean.getHeapMemoryUsage().getUsed();
        long memoryUsed = finalHeapUsage - initialHeapUsage;
        double cpuUsagePercent = (totalCpuTimeNs.get() * 100.0) / (durationNs * Runtime.getRuntime().availableProcessors());

        System.out.printf("\n=== Resource Report for '%s' ===\n", processName);
        System.out.printf("CPU Time: %.2f ms\n", totalCpuTimeNs.get() / 1_000_000.0);
        System.out.printf("CPU Usage: %.1f%%\n", cpuUsagePercent);
        System.out.printf("Memory Used: %,d bytes\n", memoryUsed);
        System.out.println("================================");
    }

    private void updateCpuTimes() {
        Set<Long> currentThreads = getAllThreadIds();

        currentThreads.forEach(id -> {
            if (!initialThreads.contains(id)) {
                // Track new threads created during monitoring
                initialCpuTimes.putIfAbsent(id, safeGetCpuTime(id));
            }

            long currentTime = safeGetCpuTime(id);
            Long initialTime = initialCpuTimes.get(id);

            if (initialTime != null && currentTime > initialTime) {
                totalCpuTimeNs.addAndGet(currentTime - initialTime);
                initialCpuTimes.put(id, currentTime);
            }
        });
    }

    private long safeGetCpuTime(long threadId) {
        try {
            return threadBean.getThreadCpuTime(threadId);
        } catch (Exception e) {
            return 0;
        }
    }

    private Set<Long> getAllThreadIds() {
        return Arrays.stream(threadBean.getAllThreadIds())
                .boxed()
                .collect(Collectors.toSet());
    }

    // Test Cases ==============================================

    public static void main(String[] args) throws Exception {
        testSingleThreadedProcess();
        testMultiThreadedProcess();
        testWithUnrelatedThreads();
    }

    private static void testSingleThreadedProcess() throws InterruptedException {
        ResourceMonitor monitor = new ResourceMonitor("SingleThreadTest");
        monitor.start();

        // Simulate CPU and memory work
        List<byte[]> data = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            data.add(new byte[1024]); // Allocate 1KB per iteration
            busyWork(100_000); // Simulate CPU load
        }

        monitor.stopAndReport();
    }

    private static void testMultiThreadedProcess() throws InterruptedException, ExecutionException {
        ResourceMonitor monitor = new ResourceMonitor("MultiThreadTest");
        monitor.start();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                List<byte[]> data = new ArrayList<>();
                for (int j = 0; j < 250; j++) {
                    data.add(new byte[1024]);
                    busyWork(25_000);
                }
            }));
        }

        for (Future<?> f : futures) f.get();
        pool.shutdown();
        monitor.stopAndReport();
    }

    private static void testWithUnrelatedThreads() throws InterruptedException {
        // Start unrelated background thread
        ExecutorService unrelatedPool = Executors.newSingleThreadExecutor();
        unrelatedPool.submit(() -> {
            while (true) {
                busyWork(1_000_000);
                Thread.sleep(100);
            }
        });

        ResourceMonitor monitor = new ResourceMonitor("WithUnrelatedThreadsTest");
        monitor.start();

        // Simulate actual work
        List<byte[]> data = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            data.add(new byte[1024]);
            busyWork(50_000);
        }

        monitor.stopAndReport();
        unrelatedPool.shutdownNow();
    }

    private static void busyWork(int iterations) {
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += Math.sqrt(i);
        }
    }
}

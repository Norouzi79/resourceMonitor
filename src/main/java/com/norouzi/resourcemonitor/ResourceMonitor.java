package com.norouzi.resourcemonitor;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;

public class ResourceMonitor {
    private final String methodName;
    private final ThreadMXBean threadBean;
    private final MemoryMXBean memoryBean;
    private long startCpuTime;
    private long startMemory;
    private final boolean cpuTimeSupported;

    public ResourceMonitor(String methodName) {
        this.methodName = methodName;
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.cpuTimeSupported = threadBean.isCurrentThreadCpuTimeSupported();

        if (cpuTimeSupported && !threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
    }

    public void start() {
        this.startCpuTime = cpuTimeSupported ? threadBean.getCurrentThreadCpuTime() : 0;
        this.startMemory = memoryBean.getHeapMemoryUsage().getUsed();
    }

    public void stopAndReport() {
        long finalCpuTime = cpuTimeSupported ? threadBean.getCurrentThreadCpuTime() : 0;
        long finalMemory = memoryBean.getHeapMemoryUsage().getUsed();

        printResults(finalCpuTime - startCpuTime, finalMemory - startMemory);
    }

    private void printResults(long cpuTimeNs, long memoryUsed) {
        System.out.println("\n--- Resource Report for " + methodName + " ---");

        if (cpuTimeSupported) {
            System.out.printf("CPU Time: %.3f ms%n", cpuTimeNs / 1_000_000.0);
        } else {
            System.out.println("CPU Time: Measurement not supported");
        }

        System.out.println("Memory Used: " + memoryUsed + " bytes");
        System.out.println("-----------------------------------");
    }

    protected static void processData() {
        // Your actual method implementation
        try {
            Thread.sleep(100); // Simulate work
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected static void validateData() {
        // Another method implementation
        try {
            Thread.sleep(50); // Simulate work
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

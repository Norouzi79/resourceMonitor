package com.norouzi.resourcemonitor;

import java.util.Date;

import static com.norouzi.resourcemonitor.ResourceMonitor.processData;
import static com.norouzi.resourcemonitor.ResourceMonitor.validateData;

public class Demo3Application {

    // Example usage in different methods
    public static void main(String[] args) {
        // Method 1 monitoring
        ResourceMonitor method1Tracker = new ResourceMonitor("dataProcessor");
        method1Tracker.start();
        processData();
        method1Tracker.stopAndReport();

        // Method 2 monitoring
        ResourceMonitor method2Tracker = new ResourceMonitor("dataValidator");
        method2Tracker.start();
        validateData();
        method2Tracker.stopAndReport();
    }

}

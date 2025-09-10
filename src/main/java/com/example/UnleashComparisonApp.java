package com.example;

import io.getunleash.DefaultUnleash;
import io.getunleash.Unleash;
import io.getunleash.UnleashContext;
import io.getunleash.UnleashException;
import io.getunleash.event.ClientFeaturesResponse;
import io.getunleash.event.UnleashSubscriber;
import io.getunleash.util.UnleashConfig;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.ref.WeakReference;

public class UnleashComparisonApp {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final long POLLING_INTERVAL_MS = 15000; // 15 seconds polling interval
    private static final long COMPARISON_DELAY_MS = POLLING_INTERVAL_MS + 5000; // Wait extra 5s for polling to catch up
    private static final long CHECK_INTERVAL_MS = 30000; // Check every 30 seconds
    
    private static Unleash streamingClient;
    private static Unleash pollingClient;
    private static final AtomicLong comparisonCount = new AtomicLong(0);
    private static final AtomicLong mismatchCount = new AtomicLong(0);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static volatile ScheduledFuture<?> pendingComparisonTask = null;
    
    // Memory monitoring
    private static final Runtime runtime = Runtime.getRuntime();
    private static long lastUsedMemory = 0;
    
    // Store latest comparison data for HTTP server
    private static volatile Map<String, Boolean> lastStreamingValues = new ConcurrentHashMap<>();
    private static volatile Map<String, Boolean> lastPollingValues = new ConcurrentHashMap<>();
    private static volatile String lastComparisonTime = "";
    private static volatile List<String> lastDiscrepancies = new CopyOnWriteArrayList<>();
    
    // Track persistent discrepancies (only count those lasting longer than refresh interval)
    // Use bounded map to prevent unbounded growth
    private static final int MAX_DISCREPANCY_ENTRIES = 1000;
    private static final Map<String, Long> discrepancyTimestamps = new ConcurrentHashMap<>();
    private static final long DISCREPANCY_THRESHOLD_MS = POLLING_INTERVAL_MS + 2000; // Wait longer than polling interval
    
    // Bounded HTTP executor to prevent thread accumulation
    private static final ExecutorService httpExecutor = Executors.newFixedThreadPool(10);
    
    private static class StreamingEventSubscriber implements UnleashSubscriber {
        @Override
        public void togglesFetched(ClientFeaturesResponse toggleResponse) {
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);
            System.out.println("[" + timestamp + "] [STREAMING] Features updated");
            
            // Update UI immediately for streaming side
            updateUIImmediate();
            
            // Cancel any existing pending comparison task to prevent task accumulation
            if (pendingComparisonTask != null && !pendingComparisonTask.isDone()) {
                pendingComparisonTask.cancel(true); // Use true to interrupt if running
            }
            
            // Schedule a new comparison after giving polling time to catch up
            pendingComparisonTask = scheduler.schedule(
                UnleashComparisonApp::compareClientsForDiscrepancies, 
                COMPARISON_DELAY_MS, 
                TimeUnit.MILLISECONDS
            );
        }

        @Override
        public void onError(UnleashException unleashException) {
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);
            System.err.println("[" + timestamp + "] [STREAMING ERROR] " + unleashException.getMessage());
        }
    }
    
    private static class PollingEventSubscriber implements UnleashSubscriber {
        @Override
        public void togglesFetched(ClientFeaturesResponse toggleResponse) {
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);
            System.out.println("[" + timestamp + "] [POLLING] Features updated");
            
            // Update UI immediately for polling side
            updateUIImmediate();
        }

        @Override
        public void onError(UnleashException unleashException) {
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);
            System.err.println("[" + timestamp + "] [POLLING ERROR] " + unleashException.getMessage());
        }
    }
    
    private static class ComparisonHttpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = generateHTML();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Refresh", "1"); // Auto-refresh every 1 second
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        
        private String generateHTML() {
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\n");
            html.append("<html>\n<head>\n");
            html.append("<title>Unleash SDK Comparison</title>\n");
            html.append("<style>\n");
            html.append("body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }\n");
            html.append("h1 { color: #333; }\n");
            html.append(".container { display: flex; gap: 20px; }\n");
            html.append(".column { flex: 1; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
            html.append(".flag { padding: 8px; margin: 4px 0; border-radius: 4px; }\n");
            html.append(".enabled { background: #d4edda; color: #155724; }\n");
            html.append(".disabled { background: #e2e3e5; color: #383d41; }\n");
            html.append(".mismatch { border: 3px solid #dc3545; font-weight: bold; }\n");
            html.append(".stats { background: white; padding: 20px; margin-bottom: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
            html.append(".discrepancy { color: #d73027; font-weight: bold; }\n");
            html.append("</style>\n");
            html.append("</head>\n<body>\n");
            
            html.append("<h1>🔄 Unleash SDK Comparison Dashboard</h1>\n");
            
            // Stats section
            html.append("<div class='stats'>\n");
            html.append("<h2>Statistics</h2>\n");
            html.append("<p><strong>Last Comparison:</strong> ").append(lastComparisonTime).append("</p>\n");
            html.append("<p><strong>Total Comparisons:</strong> ").append(comparisonCount.get()).append("</p>\n");
            html.append("<p><strong>Mismatches:</strong> ").append(mismatchCount.get()).append("</p>\n");
            double successRate = comparisonCount.get() > 0 ? 
                ((comparisonCount.get() - mismatchCount.get()) * 100.0 / comparisonCount.get()) : 100.0;
            html.append("<p><strong>Success Rate:</strong> ").append(String.format("%.2f%%", successRate)).append("</p>\n");
            
            if (!lastDiscrepancies.isEmpty()) {
                html.append("<p class='discrepancy'>⚠️ Current Discrepancies:</p>\n");
                html.append("<ul>\n");
                for (String discrepancy : lastDiscrepancies) {
                    html.append("<li class='discrepancy'>").append(discrepancy).append("</li>\n");
                }
                html.append("</ul>\n");
            }
            html.append("</div>\n");
            
            // Flags comparison
            html.append("<div class='container'>\n");
            
            // Streaming column
            html.append("<div class='column'>\n");
            html.append("<h2>🌊 Streaming Mode</h2>\n");
            html.append("<p>Total flags: ").append(lastStreamingValues.size()).append("</p>\n");
            for (Map.Entry<String, Boolean> entry : new TreeMap<>(lastStreamingValues).entrySet()) {
                String flagName = entry.getKey();
                boolean enabled = entry.getValue();
                boolean hasMismatch = lastPollingValues.containsKey(flagName) && 
                                     !lastPollingValues.get(flagName).equals(enabled);
                String cssClass = enabled ? "enabled" : "disabled";
                if (hasMismatch) cssClass += " mismatch";
                html.append("<div class='flag ").append(cssClass).append("'>")
                    .append(flagName).append(": ").append(enabled ? "✅ ENABLED" : "❌ DISABLED")
                    .append("</div>\n");
            }
            html.append("</div>\n");
            
            // Polling column
            html.append("<div class='column'>\n");
            html.append("<h2>📊 Polling Mode</h2>\n");
            html.append("<p>Total flags: ").append(lastPollingValues.size()).append("</p>\n");
            for (Map.Entry<String, Boolean> entry : new TreeMap<>(lastPollingValues).entrySet()) {
                String flagName = entry.getKey();
                boolean enabled = entry.getValue();
                boolean hasMismatch = lastStreamingValues.containsKey(flagName) && 
                                     !lastStreamingValues.get(flagName).equals(enabled);
                String cssClass = enabled ? "enabled" : "disabled";
                if (hasMismatch) cssClass += " mismatch";
                html.append("<div class='flag ").append(cssClass).append("'>")
                    .append(flagName).append(": ").append(enabled ? "✅ ENABLED" : "❌ DISABLED")
                    .append("</div>\n");
            }
            html.append("</div>\n");
            
            html.append("</div>\n"); // container
            html.append("</body>\n</html>");
            
            return html.toString();
        }
    }
    
    public static void main(String[] args) {
        String apiUrl = getOrElse("UNLEASH_API_URL", "https://app.unleash-hosted.com/demo/api");
        String apiToken = getOrElse("UNLEASH_API_TOKEN", 
            "*:development.25a06b75248528f8ca93ce179dcdd141aedfb632231e0d21fd8ff349");
        
        System.out.println("Starting Unleash Comparison App");
        System.out.println("API URL: " + apiUrl);
        System.out.println("Polling interval: " + POLLING_INTERVAL_MS + "ms");
        System.out.println("Comparison delay after streaming update: " + COMPARISON_DELAY_MS + "ms");
        System.out.println("Regular check interval: " + CHECK_INTERVAL_MS + "ms");
        System.out.println("----------------------------------------");
        
        // Initialize streaming client
        UnleashConfig streamingConfig = UnleashConfig.builder()
                .appName("comparison-app-streaming")
                .instanceId("comparison-streaming-" + UUID.randomUUID())
                .unleashAPI(apiUrl)
                .customHttpHeader("Authorization", apiToken)
               .experimentalStreamingMode()
                .subscriber(new StreamingEventSubscriber())
                .synchronousFetchOnInitialisation(true)
                .build();
        
        streamingClient = new DefaultUnleash(streamingConfig);
        System.out.println("Streaming client initialized");
        
        // Initialize polling client
        UnleashConfig pollingConfig = UnleashConfig.builder()
                .appName("comparison-app-polling")
                .instanceId("comparison-polling-" + UUID.randomUUID())
                .unleashAPI(apiUrl)
                .customHttpHeader("Authorization", apiToken)
                .fetchTogglesInterval(POLLING_INTERVAL_MS / 1000) // Convert to seconds
                .subscriber(new PollingEventSubscriber())
                .synchronousFetchOnInitialisation(true)
                .build();
        
        pollingClient = new DefaultUnleash(pollingConfig);
        System.out.println("Polling client initialized");
        
        // Start HTTP server
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/", new ComparisonHttpHandler());
            server.setExecutor(httpExecutor); // Use bounded executor
            server.start();
            System.out.println("HTTP server started on http://localhost:8080");
            System.out.println("Open your browser to view the comparison dashboard");
        } catch (IOException e) {
            System.err.println("Failed to start HTTP server: " + e.getMessage());
        }
        
        // Initial UI update and comparison
        updateUIImmediate();
        compareClientsForDiscrepancies();
        
        // Schedule periodic comparisons
        scheduler.scheduleAtFixedRate(UnleashComparisonApp::compareClientsForDiscrepancies, 
            CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // Start memory monitoring every 10 seconds
        scheduler.scheduleAtFixedRate(UnleashComparisonApp::logMemoryUsage, 
            10_000, 10_000, TimeUnit.MILLISECONDS);
        
        // Keep the app running
        System.out.println("\nSoak test started. Press Ctrl+C to exit.\n");
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            scheduler.shutdown();
            httpExecutor.shutdown();
            streamingClient.shutdown();
            pollingClient.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
                if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    httpExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Main thread interrupted");
        }
    }
    
    private static void updateUIImmediate() {
        // Update UI data immediately when either client receives updates
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        lastComparisonTime = timestamp;
        
        UnleashContext streamingContext = UnleashContext.builder()
                .userId("comparison-test-user-123")
                .sessionId("comparison-session-456")
                .addProperty("mode", "streaming")
                .build();
        
        UnleashContext pollingContext = UnleashContext.builder()
                .userId("comparison-test-user-123")
                .sessionId("comparison-session-456")
                .addProperty("mode", "polling")
                .build();
        
        // Get current values from both clients
        List<String> streamingToggles = streamingClient.more().getFeatureToggleNames();
        List<String> pollingToggles = pollingClient.more().getFeatureToggleNames();
        
        Set<String> allToggles = new HashSet<>();
        allToggles.addAll(streamingToggles);
        allToggles.addAll(pollingToggles);
        
        Map<String, Boolean> streamingValues = new HashMap<>();
        Map<String, Boolean> pollingValues = new HashMap<>();
        
        for (String toggle : allToggles) {
            streamingValues.put(toggle, streamingClient.isEnabled(toggle, streamingContext));
            pollingValues.put(toggle, pollingClient.isEnabled(toggle, pollingContext));
        }
        
        // Update UI data immediately and calculate current discrepancies
        // Only copy if values actually changed to reduce memory churn
        if (!streamingValues.equals(lastStreamingValues)) {
            lastStreamingValues = new ConcurrentHashMap<>(streamingValues);
        }
        if (!pollingValues.equals(lastPollingValues)) {
            lastPollingValues = new ConcurrentHashMap<>(pollingValues);
        }
        
        // Calculate all current discrepancies immediately (for UI display)
        List<String> currentDiscrepancies = new ArrayList<>();
        for (String toggle : allToggles) {
            boolean streamingEnabled = streamingValues.get(toggle);
            boolean pollingEnabled = pollingValues.get(toggle);
            
            if (streamingEnabled != pollingEnabled) {
                String discrepancy = String.format("Toggle '%s': streaming=%s, polling=%s", 
                    toggle, streamingEnabled, pollingEnabled);
                currentDiscrepancies.add(discrepancy);
            }
        }
        
        // Update discrepancies immediately for UI display
        lastDiscrepancies = new CopyOnWriteArrayList<>(currentDiscrepancies);
    }
    
    private static void logMemoryUsage() {
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        // Convert to MB
        long usedMB = usedMemory / (1024 * 1024);
        long totalMB = totalMemory / (1024 * 1024);
        long maxMB = maxMemory / (1024 * 1024);
        
        // Calculate change from last check
        long changeMemory = usedMemory - lastUsedMemory;
        String changeStr;
        if (changeMemory > 0) {
            changeStr = String.format("📈 +%d MB", changeMemory / (1024 * 1024));
        } else if (changeMemory < 0) {
            changeStr = String.format("📉 %d MB", changeMemory / (1024 * 1024));
        } else {
            changeStr = "➡️ 0 MB";
        }
        
        System.out.printf("[%s] 💾 Memory: %d/%d MB (max: %d MB) %s%n", 
            LocalDateTime.now().format(TIME_FORMAT), usedMB, totalMB, maxMB, changeStr);
        
        lastUsedMemory = usedMemory;
        
        // Force GC if memory usage is high
        if (usedMemory > maxMemory * 0.8) {
            System.out.println("🚨 High memory usage detected, forcing GC...");
            System.gc();
        }
    }
    
    private static void compareClientsForDiscrepancies() {
        // This method is called after delays to track persistent discrepancies
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        System.out.println("\n[" + timestamp + "] === Checking for persistent discrepancies ===");
        
        UnleashContext streamingContext = UnleashContext.builder()
                .userId("comparison-test-user-123")
                .sessionId("comparison-session-456")
                .addProperty("mode", "streaming")
                .build();
        
        UnleashContext pollingContext = UnleashContext.builder()
                .userId("comparison-test-user-123")
                .sessionId("comparison-session-456")
                .addProperty("mode", "polling")
                .build();
        
        List<String> streamingToggles = streamingClient.more().getFeatureToggleNames();
        List<String> pollingToggles = pollingClient.more().getFeatureToggleNames();
        
        Set<String> allToggles = new HashSet<>();
        allToggles.addAll(streamingToggles);
        allToggles.addAll(pollingToggles);
        
        System.out.println("Total feature toggles: " + allToggles.size());
        System.out.println("Streaming toggles: " + streamingToggles.size());
        System.out.println("Polling toggles: " + pollingToggles.size());
        
        boolean hasDiscrepancies = false;
        List<String> currentDiscrepancies = new ArrayList<>();
        long currentTime = System.currentTimeMillis();
        
        // Compare toggle lists
        if (!streamingToggles.equals(pollingToggles)) {
            Set<String> streamingSet = new HashSet<>(streamingToggles);
            Set<String> pollingSet = new HashSet<>(pollingToggles);
            
            Set<String> onlyInStreaming = new HashSet<>(streamingSet);
            onlyInStreaming.removeAll(pollingSet);
            if (!onlyInStreaming.isEmpty()) {
                String discrepancy = "Toggles only in streaming: " + onlyInStreaming;
                if (isPersistentDiscrepancy("list-streaming", discrepancy, currentTime)) {
                    hasDiscrepancies = true;
                    currentDiscrepancies.add(discrepancy);
                }
            }
            
            Set<String> onlyInPolling = new HashSet<>(pollingSet);
            onlyInPolling.removeAll(streamingSet);
            if (!onlyInPolling.isEmpty()) {
                String discrepancy = "Toggles only in polling: " + onlyInPolling;
                if (isPersistentDiscrepancy("list-polling", discrepancy, currentTime)) {
                    hasDiscrepancies = true;
                    currentDiscrepancies.add(discrepancy);
                }
            }
        }
        
        // Compare isEnabled values and track persistent discrepancies
        for (String toggle : allToggles) {
            boolean streamingEnabled = streamingClient.isEnabled(toggle, streamingContext);
            boolean pollingEnabled = pollingClient.isEnabled(toggle, pollingContext);
            
            if (streamingEnabled != pollingEnabled) {
                String discrepancy = String.format("Toggle '%s': streaming=%s, polling=%s", 
                    toggle, streamingEnabled, pollingEnabled);
                if (isPersistentDiscrepancy(toggle, discrepancy, currentTime)) {
                    hasDiscrepancies = true;
                    currentDiscrepancies.add(discrepancy);
                }
            } else {
                // Remove from tracking if they match now
                discrepancyTimestamps.remove(toggle);
            }
        }
        
        // Update UI with current values and persistent discrepancies only
        Map<String, Boolean> streamingValues = new HashMap<>();
        Map<String, Boolean> pollingValues = new HashMap<>();
        for (String toggle : allToggles) {
            streamingValues.put(toggle, streamingClient.isEnabled(toggle, streamingContext));
            pollingValues.put(toggle, pollingClient.isEnabled(toggle, pollingContext));
        }
        
        // Only update if values changed to reduce memory churn
        if (!streamingValues.equals(lastStreamingValues)) {
            lastStreamingValues = new ConcurrentHashMap<>(streamingValues);
        }
        if (!pollingValues.equals(lastPollingValues)) {
            lastPollingValues = new ConcurrentHashMap<>(pollingValues);
        }
        lastDiscrepancies = new CopyOnWriteArrayList<>(currentDiscrepancies);
        lastComparisonTime = timestamp;
        
        comparisonCount.incrementAndGet();
        
        if (hasDiscrepancies) {
            mismatchCount.incrementAndGet();
            System.out.println("⚠️  PERSISTENT DISCREPANCIES FOUND:");
            for (String discrepancy : currentDiscrepancies) {
                System.out.println("  - " + discrepancy);
            }
        } else {
            System.out.println("✅ No persistent discrepancies detected!");
        }
        
        // Clean up old discrepancy timestamps and enforce size limit
        discrepancyTimestamps.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > DISCREPANCY_THRESHOLD_MS * 2);
        
        // Enforce max size limit to prevent unbounded growth
        if (discrepancyTimestamps.size() > MAX_DISCREPANCY_ENTRIES) {
            // Remove oldest entries if we exceed the limit
            List<Map.Entry<String, Long>> entries = new ArrayList<>(discrepancyTimestamps.entrySet());
            entries.sort(Map.Entry.comparingByValue()); // Sort by timestamp (oldest first)
            int toRemove = discrepancyTimestamps.size() - MAX_DISCREPANCY_ENTRIES + 100; // Remove extra to avoid frequent cleanup
            for (int i = 0; i < toRemove && i < entries.size(); i++) {
                discrepancyTimestamps.remove(entries.get(i).getKey());
            }
            System.out.println("⚠️ Discrepancy map exceeded size limit, removed " + toRemove + " oldest entries");
        }
    }
    
    private static boolean isPersistentDiscrepancy(String key, String discrepancy, long currentTime) {
        if (!discrepancyTimestamps.containsKey(key)) {
            // Check size limit before adding new entries
            if (discrepancyTimestamps.size() >= MAX_DISCREPANCY_ENTRIES) {
                System.out.println("⚠️ Discrepancy tracking map at capacity, skipping new discrepancy: " + key);
                return false;
            }
            // First time seeing this discrepancy
            discrepancyTimestamps.put(key, currentTime);
            System.out.println("New discrepancy detected (waiting for persistence): " + discrepancy);
            return false;
        } else {
            // Check if it's been persistent long enough
            long discrepancyAge = currentTime - discrepancyTimestamps.get(key);
            if (discrepancyAge >= DISCREPANCY_THRESHOLD_MS) {
                return true; // It's persistent, count it
            } else {
                System.out.println("Temporary discrepancy (age: " + discrepancyAge + "ms): " + discrepancy);
                return false;
            }
        }
    }
    
    public static String getOrElse(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value;
    }
}
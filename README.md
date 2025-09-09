# Unleash SDK Comparison App

A Java application that compares Unleash SDK behavior between streaming and polling modes to ensure consistency.

## Purpose

This soak test application monitors feature flags using both streaming and polling modes simultaneously, comparing their `isEnabled` values over time to verify that both modes return consistent results.

## Features

- Runs two Unleash clients in parallel (streaming and polling)
- Compares `isEnabled` values for all available feature toggles
- Accounts for polling delay when comparing values
- Provides statistics on comparison success rate
- Runs continuously as a long-running soak test
- **HTTP Dashboard**: Visual web interface on http://localhost:8080 showing:
  - Side-by-side comparison of flag states
  - Visual highlighting of mismatches
  - Real-time statistics and success rate
  - Auto-refreshes every 5 seconds

## Configuration

Set the following environment variables:

```bash
export UNLEASH_API_URL="https://your-unleash-instance.com/api"
export UNLEASH_API_TOKEN="your-api-token"
```

If not set, the app will use the Unleash demo instance by default.

## Running the Application

### Using the provided script:
```bash
./run.sh
```

### Using Maven directly:
```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="com.example.UnleashComparisonApp"
```

## How It Works

1. **Dual Client Setup**: Creates two Unleash clients - one in streaming mode, one in polling mode
2. **Event-Driven Comparison**: When streaming receives updates, waits for polling interval + 5s before comparing
3. **Regular Checks**: Performs comparisons every 30 seconds regardless of updates
4. **Statistics**: Reports success rate and mismatch details every minute

## Timing Configuration

- **Polling Interval**: 15 seconds
- **Comparison Delay**: 20 seconds (polling interval + 5s buffer)
- **Regular Check Interval**: 30 seconds
- **Statistics Report**: Every 60 seconds

## Output

The application provides:
- Real-time update notifications from both clients in the console
- Comparison results showing any discrepancies
- Periodic statistics showing success rate
- Details of any mismatches found
- **Web Dashboard** at http://localhost:8080 with:
  - Visual comparison of all flags
  - Red highlighting for mismatches
  - Green/red backgrounds for enabled/disabled states
  - Live statistics and discrepancy details

## Requirements

- Java 11 or higher
- Maven 3.6 or higher
- Network access to Unleash server
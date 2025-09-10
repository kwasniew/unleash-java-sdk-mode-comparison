#!/bin/bash

# Check if environment variables are set
if [ -z "$UNLEASH_API_URL" ]; then
    echo "Warning: UNLEASH_API_URL not set, using default demo API"
fi

if [ -z "$UNLEASH_API_TOKEN" ]; then
    echo "Warning: UNLEASH_API_TOKEN not set, using default demo token"
fi

# Build the project using Maven Wrapper (works without Maven installed)
echo "Building the project..."
./mvnw clean compile

# Run the application
echo "Starting Unleash Comparison App..."
./mvnw exec:java -Dexec.mainClass="com.example.UnleashComparisonApp"
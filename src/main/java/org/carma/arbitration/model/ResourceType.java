package org.carma.arbitration.model;

/**
 * Types of resources that can be allocated by the arbitration platform.
 * Each resource type has a display name and description for documentation.
 */
public enum ResourceType {
    // Infrastructure Resources
    COMPUTE("Compute Units", "CPU/GPU compute capacity"),
    STORAGE("Storage Units", "Persistent storage capacity"),
    MEMORY("Memory Units", "RAM allocation"),
    NETWORK("Network Bandwidth", "Network I/O capacity"),
    
    // Data Resources
    DATASET("Dataset Access", "Access to shared datasets"),
    
    // Service Resources  
    API_CREDITS("API Credits", "External API call quota");

    private final String displayName;
    private final String description;

    ResourceType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

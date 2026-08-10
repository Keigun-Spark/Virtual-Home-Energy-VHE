package org.vhe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages virtual energy calculations and coordinates with the Homematic IP Connect API.
 * Keeps track of all devices with power measurement capabilities and calculates the
 * "unaccounted" household energy by balancing Grid, PV, and measured consumers.
 */
public class EnergyManager {

    public enum DeviceRole {
        GRID, PV, MEASURED, IGNORE, AUTO
    }

    public static class DeviceState {
        public DeviceRole role;
        public double power;
        public String name;

        public DeviceState(DeviceRole role, double power, String name) {
            this.role = role;
            this.power = power;
            this.name = name;
        }
    }

    private final HmipClient client;
    private final Map<String, DeviceState> devices = new HashMap<>();
    private final String virtualDeviceId = "vhe-unaccounted-energy-v1";

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean updatePending = false;
    private String assignedVirtualHcuId = null;

    private double totalEnergyInKWh = 0;
    private double totalEnergyOutKWh = 0;
    private long lastUpdateTime = System.currentTimeMillis();

    private double unitPrice = 0;
    private String currency = "EUR";

    private Map<String, String> deviceConfig = new HashMap<>();
    private final File configPath = new File("device-config.json");

    public EnergyManager(HmipClient client) {
        this.client = client;
    }

    /**
     * Initializes the Energy Manager, loads configuration, and registers all Homematic IP event handlers.
     */
    public void initialize() {
        System.out.print("Initializing Energy Manager...\n");

        loadConfiguration();
        loadInitialSystemState();
        registerEventHandlers();

        client.send("PLUGIN_STATE_RESPONSE", Map.of(
            "pluginReadinessStatus", "READY",
            "friendlyName", Map.of(
                "en", "Virtual Home Energy",
                "de", "Virtuelle Hausenergie"
            )
        ));

        triggerUpdate();
    }

    /**
     * Gracefully shuts down the internal scheduler.
     */
    public void shutdown() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    /**
     * Loads the manual device role configurations from the filesystem (e.g. device-config.json).
     */
    private void loadConfiguration() {
        try {
            if (configPath.exists()) {
                deviceConfig = HmipClient.MAPPER.readValue(configPath, new TypeReference<Map<String, String>>() {});
            }
        } catch (Exception e) {
            System.err.print("Failed to load device-config.json: " + e.getMessage() + "\n");
        }
    }

    /**
     * Fetches the initial state from the Home Control Unit and populates the local device registry.
     */
    private void loadInitialSystemState() {
        try {
            JsonNode state = client.getSystemState().get();
            if (state == null) return;

            JsonNode devicesMap = state.has("devices") ? state.get("devices") : 
                                 (state.has("body") && state.get("body").has("devices") ? state.get("body").get("devices") : null);
            
            JsonNode home = state.has("home") ? state.get("home") : 
                           (state.has("body") && state.get("body").has("home") ? state.get("body").get("home") : null);

            if (home != null) {
                if (home.has("powerMeterUnitPrice")) {
                    unitPrice = home.get("powerMeterUnitPrice").asDouble();
                }
                if (home.has("powerMeterCurrency")) {
                    currency = home.get("powerMeterCurrency").asText();
                }
            }

            if (devicesMap != null && devicesMap.isObject()) {
                devicesMap.fields().forEachRemaining(entry -> processDevice(entry.getValue()));
            }
        } catch (Exception e) {
            System.err.print("Failed to fetch initial system state: " + e.getMessage() + "\n");
        }
    }

    /**
     * Registers all WebSocket event handlers required for the plugin lifecycle and data updates.
     */
    private void registerEventHandlers() {
        client.on("HMIP_SYSTEM_EVENT", msg -> handleSystemEvent(msg.getBody()));

        client.on("HMIP_SYSTEM_REQUEST", msg -> {
            JsonNode body = msg.getBody();
            if (body != null && body.has("path") && "/hmip/device/control/resetEnergyCounter".equals(body.get("path").asText())) {
                JsonNode innerBody = body.get("body");
                if (innerBody != null && innerBody.has("deviceId")) {
                    String targetId = innerBody.get("deviceId").asText();
                    if (virtualDeviceId.equals(targetId) || targetId.equals(assignedVirtualHcuId)) {
                        resetEnergyCounters();
                    }
                }
            }
        });

        client.on("DISCOVER_REQUEST", msg -> {
            client.send("DISCOVER_RESPONSE", Map.of(
                "success", true,
                "devices", List.of(buildDeviceProfile())
            ), msg.getId());
        });

        client.on("INCLUSION_EVENT", msg -> {
            client.send("STATUS_RESPONSE", Map.of(
                "devices", List.of(buildDeviceProfile())
            ));
        });

        client.on("STATUS_REQUEST", msg -> {
            client.send("STATUS_RESPONSE", Map.of(
                "devices", List.of(buildDeviceProfile())
            ), msg.getId());
        });

        client.on("CONFIG_TEMPLATE_REQUEST", msg -> handleConfigTemplateRequest(msg));
        client.on("CONFIG_UPDATE_REQUEST", msg -> handleConfigUpdateRequest(msg));

        client.on("PLUGIN_STATE_REQUEST", msg -> {
            client.send("PLUGIN_STATE_RESPONSE", Map.of(
                "pluginReadinessStatus", "READY",
                "friendlyName", Map.of(
                    "en", "Virtual Home Energy",
                    "de", "Virtuelle Hausenergie"
                )
            ), msg.getId());
        });
    }

    private void resetEnergyCounters() {
        System.out.print("Received command to reset energy counter. Resetting to 0.\n");
        totalEnergyInKWh = 0;
        totalEnergyOutKWh = 0;

        client.sendStatusEvent(virtualDeviceId, List.of(
            Map.of(
                "type", "energyCounter",
                "in", 0,
                "out", 0
            )
        ));
    }

    private void handleConfigTemplateRequest(HmipMessage msg) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();

        int order = 1;
        for (Map.Entry<String, DeviceState> entry : devices.entrySet()) {
            String id = entry.getKey();
            DeviceState state = entry.getValue();

            String currentValue = "AUTO";
            if (deviceConfig.containsKey(id)) {
                currentValue = deviceConfig.get(id).toUpperCase();
            }

            properties.put(id, Map.of(
                "friendlyName", state.name != null ? state.name : "Unknown Device",
                "description", "Select the role for this energy device (Current heuristic role: " + state.role.name() + ")",
                "dataType", "ENUM",
                "values", List.of("AUTO", "GRID", "PV", "MEASURED", "IGNORE"),
                "defaultValue", "AUTO",
                "currentValue", currentValue,
                "required", true,
                "order", order++
            ));
        }

        if (properties.isEmpty()) {
            properties.put("noDevices", Map.of(
                "friendlyName", "No devices found",
                "description", "No energy measurement devices were detected.",
                "dataType", "READONLY",
                "currentValue", "Wait for devices to connect.",
                "order", 1
            ));
        }

        client.send("CONFIG_TEMPLATE_RESPONSE", Map.of(
            "properties", properties
        ), msg.getId());
    }

    private void handleConfigUpdateRequest(HmipMessage msg) {
        JsonNode body = msg.getBody();
        if (body != null && body.has("properties")) {
            JsonNode props = body.get("properties");
            boolean changed = false;
            if (props.isObject()) {
                java.util.Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String deviceId = entry.getKey();
                    String value = entry.getValue().asText();
                    if ("AUTO".equals(value)) {
                        if (deviceConfig.containsKey(deviceId)) {
                            deviceConfig.remove(deviceId);
                            changed = true;
                        }
                    } else {
                        if (!value.equals(deviceConfig.get(deviceId))) {
                            deviceConfig.put(deviceId, value);
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                saveConfiguration();
                triggerUpdate();
            }
        }

        client.send("CONFIG_UPDATE_RESPONSE", Map.of(
            "status", "APPLIED",
            "message", "Configuration successfully saved."
        ), msg.getId());
    }

    private void saveConfiguration() {
        try {
            HmipClient.MAPPER.writeValue(configPath, deviceConfig);
            System.out.print("Device configuration saved successfully.\n");
        } catch (Exception e) {
            System.err.print("Failed to save device-config.json: " + e.getMessage() + "\n");
        }
    }

    private Map<String, Object> buildDeviceProfile() {
        return Map.of(
            "deviceId", virtualDeviceId,
            "deviceType", "HVAC",
            "modelType", "VIRTUAL_ENERGY_METER",
            "friendlyName", "Remaining household consumption",
            "firmwareVersion", "1.0.0",
            "features", List.of(
                Map.of(
                    "type", "currentPower",
                    "currentPower", roundTwo(calculateUnaccountedEnergy())
                ),
                Map.of(
                    "type", "energyCounter",
                    "in", roundFour(totalEnergyInKWh),
                    "out", roundFour(totalEnergyOutKWh)
                )
            )
        );
    }

    /**
     * Processes an individual device payload from the HCU, identifying its role and current power.
     * Heuristics are used to automatically detect GRID, PV, or MEASURED consumer devices.
     */
    private void processDevice(JsonNode device) {
        if (!device.has("functionalChannels") || !device.get("functionalChannels").isObject()) return;

        String id = device.path("id").asText("");
        String modelType = device.path("modelType").asText("");

        if ("VIRTUAL_ENERGY_METER".equals(modelType) || virtualDeviceId.equals(id) || id.equals(assignedVirtualHcuId)) {
            if (assignedVirtualHcuId == null && !virtualDeviceId.equals(id)) {
                assignedVirtualHcuId = id;

                device.get("functionalChannels").elements().forEachRemaining(ch -> {
                    if (ch.has("energyCounter")) totalEnergyInKWh = ch.get("energyCounter").asDouble();
                    else if (ch.has("energyCounterOne")) totalEnergyInKWh = ch.get("energyCounterOne").asDouble();
                    
                    if (ch.has("energyCounterTwo")) totalEnergyOutKWh = ch.get("energyCounterTwo").asDouble();
                });
            }
            return;
        }

        double totalPower = 0;
        boolean hasPower = false;
        StringBuilder roleStr = new StringBuilder(device.path("type").asText(""));

        for (JsonNode channel : device.get("functionalChannels")) {
            if (channel.has("currentPowerConsumption")) {
                totalPower += channel.get("currentPowerConsumption").asDouble();
                hasPower = true;
            } else if (channel.has("currentPower")) {
                totalPower += channel.get("currentPower").asDouble();
                hasPower = true;
            }

            roleStr.append(" ")
                   .append(channel.path("energyMeterRole").asText(""))
                   .append(" ").append(channel.path("connectedEnergySensorType").asText(""))
                   .append(" ").append(channel.path("powerMeasuringCategory").asText(""))
                   .append(" ").append(channel.path("energyMeterMode").asText(""))
                   .append(" ").append(channel.path("channelRole").asText(""));
        }

        if (!hasPower) return;

        String rStr = roleStr.toString().toUpperCase();
        DeviceRole role = DeviceRole.MEASURED;

        if (rStr.contains("GRID")) {
            role = DeviceRole.GRID;
        } else if (rStr.contains("INVERTER") || rStr.contains("PV") || rStr.contains("PRODUCER") || rStr.contains("FEED_IN") || rStr.contains("INJECTION")) {
            role = DeviceRole.PV;
        }

        String name = device.has("label") ? device.get("label").asText() : (modelType.isEmpty() ? "Unknown Device" : modelType);
        devices.put(id, new DeviceState(role, totalPower, name));
    }

    private void handleSystemEvent(JsonNode body) {
        if (body == null || !body.has("eventTransaction") || !body.get("eventTransaction").has("events")) return;
        JsonNode events = body.get("eventTransaction").get("events");
        if (!events.isObject()) return;

        boolean changed = false;
        for (JsonNode event : events) {
            String pushEventType = event.has("pushEventType") ? event.get("pushEventType").asText() : "";
            if ("DEVICE_CHANGED".equals(pushEventType) || "DEVICE_ADDED".equals(pushEventType)) {
                if (event.has("device")) {
                    processDevice(event.get("device"));
                    changed = true;
                }
            } else if ("DEVICE_REMOVED".equals(pushEventType)) {
                String id = event.has("id") ? event.get("id").asText() : null;
                if (id != null && devices.remove(id) != null) {
                    changed = true;
                }
            }
        }

        if (changed) {
            triggerUpdate();
        }
    }

    private synchronized void triggerUpdate() {
        if (updatePending) return;
        updatePending = true;

        scheduler.schedule(() -> {
            synchronized (EnergyManager.this) {
                updatePending = false;
                long now = System.currentTimeMillis();
                double elapsedHours = (now - lastUpdateTime) / 3600000.0;
                lastUpdateTime = now;

                EnergyBreakdown breakdown = calculateEnergyBreakdown();
                double total = breakdown.total;

                double energyKWhDelta = (total * elapsedHours) / 1000.0;
                if (total > 0) {
                    totalEnergyInKWh += energyKWhDelta;
                } else if (total < 0) {
                    totalEnergyOutKWh += Math.abs(energyKWhDelta);
                }

                double costIn = totalEnergyInKWh * unitPrice;

                System.out.print(String.format("Energy Breakdown -> Grid: %.2fW, PV: %.2fW, Measured: %.2fW => Unaccounted: %.2fW (In: %.4fkWh / %.2f %s, Out: %.4fkWh)%n",
                        breakdown.grid, breakdown.pv, breakdown.measured, total, totalEnergyInKWh, costIn, currency, totalEnergyOutKWh));

                client.sendStatusEvent(virtualDeviceId, List.of(
                    Map.of(
                        "type", "currentPower",
                        "currentPower", roundTwo(total)
                    ),
                    Map.of(
                        "type", "energyCounter",
                        "in", roundFour(totalEnergyInKWh),
                        "out", roundFour(totalEnergyOutKWh)
                    )
                ));
            }
        }, 10, TimeUnit.SECONDS);
    }

    private double calculateUnaccountedEnergy() {
        return calculateEnergyBreakdown().total;
    }

    private static class EnergyBreakdown {
        double total;
        double grid;
        double pv;
        double measured;

        EnergyBreakdown(double total, double grid, double pv, double measured) {
            this.total = total;
            this.grid = grid;
            this.pv = pv;
            this.measured = measured;
        }
    }

    private EnergyBreakdown calculateEnergyBreakdown() {
        double grid = 0;
        double pv = 0;
        double measured = 0;

        for (Map.Entry<String, DeviceState> entry : devices.entrySet()) {
            DeviceState state = entry.getValue();
            String id = entry.getKey();
            
            DeviceRole role = state.role;
            if (deviceConfig.containsKey(id)) {
                try {
                    role = DeviceRole.valueOf(deviceConfig.get(id).toUpperCase());
                } catch (IllegalArgumentException ignored) {}
            }

            switch (role) {
                case GRID:
                    grid += state.power;
                    break;
                case PV:
                    pv += state.power;
                    break;
                case MEASURED:
                    measured += state.power;
                    break;
                case IGNORE:
                default:
                    break;
            }
        }

        // Account for grid flow polarity. If grid > 0, we are importing (consuming)
        // If grid < 0, we are exporting.
        double total = grid + Math.abs(pv) - measured;
        return new EnergyBreakdown(total, grid, pv, measured);
    }

    private double roundTwo(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double roundFour(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}

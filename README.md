# Virtual Home Energy (VHE) Plugin - HCU Docker Plugin

Contains the build configuration to package the Virtual Home Energy plugin as a deployable Docker container for the **Homematic IP Home Control Unit (HCU)**. It automatically calculates your household's "unaccounted" or "unknown" energy consumption and creates a virtual energy meter in the Homematic IP App.

## How it works

The VHE plugin connects locally to the HCU Connect API. It subscribes to all energy measurement events from your Homematic IP devices (e.g., HmIP-PSM, HmIP-FSM) and automatically categorizes them based on their configuration and channel roles into:
- **Grid / Main Meter** (Grid import and export)
- **PV / Inverter** (Solar power production)
- **Measured Consumers** (Devices with known consumption)

It calculates the remaining unknown consumption using the formula:
`Unaccounted Energy = (Grid Power) + (PV Power) - (Sum of Consumer Power)`

This value is then fed into a newly created Virtual Energy Meter device (`VIRTUAL_ENERGY_METER`) that appears in your Homematic IP App just like a real device. It fully integrates with the platform's cost calculation and energy statistics.

## Features

- **Zero-Config Discovery:** Automatically discovers your grid meters and PV inverters based on device configurations.
- **Cost Tracking Integration:** Uses the energy price configured in the Homematic IP app to calculate the cost of the unaccounted energy.
- **Local Execution:** Runs securely as a container directly on your Home Control Unit. No cloud connection required.
- **Efficient:** Calculates power continuously on device state changes and maintains precise running energy counters.

## Installation

1. Navigate to the Web UI of your Home Control Unit (ensure **Developer Mode** is active).
2. Go to the **Plugins** section.
3. Upload the `virtual-home-energy-latest.tar.gz` archive.
4. Activate the plugin. 
5. Open the Homematic IP app. You will see a new device in your inbox called "Remaining household consumption".

## Advanced Configuration

By default, the plugin uses automatic heuristics to determine if a meter is a grid meter or a PV inverter. If your setup is not detected correctly, you can manually override roles by creating a `device-config.json` inside the plugin's persistent data directory (`/data/device-config.json`) on the HCU.

Example `device-config.json`:
```json
{
  "3210F765A000000000000001": "GRID",
  "3210F765A000000000000002": "PV",
  "3210F765A000000000000003": "IGNORE"
}
```

## Build and Package

To build the plugin from source, you need Java 11+, Maven, and Docker installed.

```bash
mvn clean package docker:build docker:save
```

This will produce the deployable tarball in `target/virtual-home-energy-latest.tar.gz`.

## License

This project is open-source.


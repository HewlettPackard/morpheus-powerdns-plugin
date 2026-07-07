# Morpheus PowerDNS Plugin

This plugin provides a DNS integration between [PowerDNS](https://www.powerdns.com/) and [Morpheus](https://morpheusdata.com). It enables DNS zone sync, DNS record sync, DNS record creation and removal, and optional pointer-record creation from within the Morpheus platform.

## Requirements

| Component | Minimum Version |
|-----------|----------------|
| Morpheus | 7.0.2 |

## Installation

1. Download the latest `.jar` from the [Releases](https://github.com/HewlettPackard/morpheus-powerdns-plugin/releases) page, or [build it yourself](#building).
2. In Morpheus, navigate to **Administration → Integrations → Plugins**.
3. Click **Browse** and upload the `.jar` file.
4. The **PowerDNS** integration type will appear after the plugin loads.

## Configuration

When adding a PowerDNS integration in Morpheus (**Administration → Integrations → Add Integration**), provide the following:

| Field | Description |
|-------|-------------|
| **API Url** | PowerDNS API endpoint. HTTPS is recommended. |
| **Credentials** | Morpheus API key credential for the PowerDNS API token. |
| **Token** | Local PowerDNS API token field used when not selecting a stored credential. |
| **Service Version** | PowerDNS API version to use: `3` or `4`. |
| **Create Pointers** | Attempt to create pointer records when creating DNS records. |
| **Domain Active** | Mark synced DNS domains active by default in Morpheus. |

## Features

### DNS Management
The plugin registers a `DNSProvider` for PowerDNS. Supported operations include:

- Create DNS records through the PowerDNS API
- Remove DNS records through the PowerDNS API
- Use PowerDNS API v3 or v4 request formats based on the configured service version
- Optionally request pointer creation when creating records
- Store synced record comments, TTLs, types, and content in Morpheus

### DNS Sync
The following resources are discovered and kept in sync from PowerDNS:

- **DNS Zones** — authoritative zones returned by the PowerDNS server
- **DNS Records** — record sets within each synced zone
- **Zone Metadata** — zone type, serial, DNSSEC flag, FQDN, and active state

Any additions, updates, and removals in PowerDNS are reflected in Morpheus on the next integration refresh.

## Building

```bash
./gradlew shadowJar
```

The plugin JAR will be written to `build/libs/`.

## License

Copyright 2024 Morpheus Data, LLC. Licensed under the [Apache License, Version 2.0](LICENSE).

# Morpheus PowerDNS Plugin

The Morpheus PowerDNS Plugin integrates Morpheus with PowerDNS Authoritative Server to provide DNS record automation. The plugin communicates with the PowerDNS HTTP API to create and delete DNS records in PowerDNS zones when instances are provisioned or decommissioned.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Repository structure](#repository-structure)
- [Building the plugin](#building-the-plugin)
- [License](#license)
- [Installing](#installing)
- [Detailed Usage Steps](#detailed-usage-steps)
- [API Endpoints](#api-endpoints)

---

## Features

### DNS Record Management

Create and delete DNS resource records in PowerDNS zones from Morpheus. Records are managed automatically during instance provisioning and decommissioning. Supports optional PTR (pointer) record creation.

### Cloud Sync

Morpheus synchronises the following PowerDNS resources for inventory:

- DNS zones (from the `localhost` server)
- DNS records within each zone

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Morpheus | 7.0.2 or later |
| Java | 11 or later |
| Gradle | Use the included Gradle wrapper (`./gradlew`) |

Additional prerequisites:

- A running PowerDNS Authoritative Server with the HTTP API enabled and accessible from the Morpheus appliance
- A PowerDNS API key with read/write access
- Network access from the Morpheus appliance to the PowerDNS API host on the configured port

---

## Repository structure

```
src/main/groovy/com/morpheusdata/powerdns/
├── PowerDnsPlugin.groovy         - Plugin entry point; registers PowerDnsProvider and PowerDnsOptionProvider
├── PowerDnsProvider.groovy       - DNSProvider implementation; DNS operations, sync, OptionTypes
└── PowerDnsOptionProvider.groovy - UI option source data
build.gradle, gradle.properties   - Build configuration and plugin metadata
```

---

## Building the plugin

Run the following command to compile and package the plugin jar:

```bash
./gradlew clean build
```

The packaged jar will be written to `build/libs/`.

To execute tests, use the following command:

```bash
./gradlew test
```

---

## License

This project is licensed under the Apache License 2.0.

See the [LICENSE](LICENSE) file for details.

---

## Installing

1. Build the plugin (see [Building the plugin](#building-the-plugin)) or download a released jar.
2. In Morpheus, navigate to **Administration > Integrations > Plugins**.
3. Click **Add** and upload the `morpheus-powerdns-plugin-<version>.jar` from `build/libs/`.
4. Navigate to **Infrastructure > DNS > Add** and select **PowerDNS** to configure the integration.

---

## Detailed Usage Steps

### Adding a PowerDNS Integration

1. Go to **Infrastructure > DNS > Add**.
2. Select **PowerDNS** as the DNS provider type.
3. Configure:
   - **API Url** — PowerDNS API base URL (e.g. `https://pdns.example.com`)
   - **Credentials** — select **API Key** and provide the PowerDNS API key as the **Token**
   - **Service Version** — PowerDNS API version (`v1` recommended)
   - **Create Pointers** — enable to automatically create PTR records
   - **Domain Active** — enable to activate the domain integration
4. Save. Morpheus syncs zones from the PowerDNS `localhost` server.

### Creating DNS Records Automatically

When an instance is provisioned on a network with PowerDNS configured, Morpheus creates the DNS record by sending a PATCH request to the appropriate zone endpoint with the new record set.

### Removing DNS Records

When an instance is decommissioned, Morpheus sends a PATCH request to the zone endpoint to remove the record set.

---

## API Endpoints

This plugin communicates with the **PowerDNS HTTP API** at the configured API Url. Authentication uses the `X-API-KEY` header.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/servers/localhost/zones` | GET | List all zones |
| `/api/v1/servers/localhost/zones/{zoneId}` | GET | Get zone details and records |
| `/api/v1/servers/localhost/zones/{zoneId}` | PATCH | Create or delete a resource record set |

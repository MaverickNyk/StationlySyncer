# Stationly Syncer

A high-performance Spring Boot middleware for Transport for London (TfL) data integration. Provides real-time arrival predictions, station metadata, and live line status updates.

## Features

- **Real-time Predictions** - Accurate arrival times for Tube, Overground, DLR, and more
- **Station Metadata** - Detailed station information including coordinates and transport modes
- **Line Status** - Live updates on delays, closures, and service changes
- **Push Notifications** - Firebase Cloud Messaging (FCM) for real-time updates
- **OCI Monitoring** - Integrated metrics publishing to Oracle Cloud Infrastructure

## Tech Stack

- Java 17
- Spring Boot 3.4.0
- Firebase Admin SDK
- Oracle Cloud Infrastructure SDK
- Scalar (OpenAPI Documentation)

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+
- Firebase service account credentials
- TfL API key

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `TFL_APP_KEY` | TfL API authentication key | - |
| `TFL_TRANSPORT_MODES` | Comma-separated transport modes | `tube,overground,dlr` |
| `TFL_POLLING_INTERVAL` | Polling interval in ms | `30000` |
| `FCM_SERVICE_ACCOUNT_PATH` | Path to Firebase credentials | - |
| `FCM_SERVICE_ACCOUNT_JSON` | Firebase credentials as JSON string | - |
| `FIREBASE_DATABASE_URL` | Firebase Realtime Database URL | - |
| `OCI_MONITORING_ENABLED` | Enable OCI metrics | `false` |
| `OCI_MONITORING_COMPARTMENT_ID` | OCI compartment ID | - |

### Local Development

```bash
# Run with local profile
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Build

```bash
mvn clean package -DskipTests
```

## API Documentation

Once running, access the interactive API documentation at:

- **Local**: http://localhost:8080/StationlySyncer/docs
- **Production**: https://api.stationly.co.uk/StationlySyncer/docs

## Deployment

### Oracle Cloud Deployment

```bash
./deploy.sh
```

This script:
1. Builds the application
2. Merges `application.properties` with `application-remote.properties`
3. Copies JAR and config to the remote server
4. Restarts the systemd service

### Server Configuration

See `server-config/` for nginx and systemd configuration files.

## Project Structure

```
├── src/main/java/com/stationly/backend/
│   ├── config/          # Spring configuration classes
│   ├── controller/      # REST API controllers
│   ├── service/         # Business logic services
│   ├── model/           # Data models
│   ├── client/          # External API clients
│   └── exception/       # Exception handling
├── src/main/resources/
│   ├── application.properties         # Base configuration
│   ├── application-local.properties   # Local dev overrides
│   └── application-remote.properties  # Production overrides
├── server-config/       # Server configuration files
│   └── nginx/           # Nginx configuration
└── deploy.sh            # Deployment script
```

## License

Apache 2.0

## Contact

Stationly Limited - support@stationly.co.uk

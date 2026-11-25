# Bagade Backend

Backend service for the Clothing Shop Inventory and Billing System built with Java 21, Spring Boot, PostgreSQL, and Flyway.

## Prerequisites

- Java 21
- PostgreSQL 16 (running locally or accessible via network)

## Local Setup

1. Create local PostgreSQL databases (e.g., `bagade_dev` for running locally and `bagade_test` for running tests):

```sql
CREATE DATABASE bagade_dev;
CREATE DATABASE bagade_test;
```

2. Configure environment variables before starting the application.

## Environment Variables

| Variable | Description | Default (Local) |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/bagade_dev` |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL username | `saurabhasalkar` |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password | *(empty)* |
| `PORT` | HTTP server port | `8080` |
| `SESSION_COOKIE_SECURE` | Set `Secure` flag on `JSESSIONID` cookie (`true` for HTTPS) | `false` |
| `APP_FIRST_OWNER_USERNAME` | Initial owner username for idempotent provisioning | *(empty)* |
| `APP_FIRST_OWNER_PASSWORD` | Initial owner password (never logged or committed) | *(empty)* |
| `APP_FIRST_OWNER_DISPLAY_NAME` | Initial owner display name | `Owner` |

### Local HTTP Cookie Configuration

For local HTTP development (`http://localhost:8080`), `SESSION_COOKIE_SECURE` defaults to `false` so browsers accept the `HttpOnly`, `SameSite=Lax` `JSESSIONID` cookie over plain HTTP. Set `SESSION_COOKIE_SECURE=true` in HTTPS deployments.

## First-Owner Provisioning

On startup, `FirstOwnerInitializer` checks `APP_FIRST_OWNER_USERNAME` and `APP_FIRST_OWNER_PASSWORD`. If both are non-blank and no user with that username exists, it creates an active `OWNER` account using Spring Security's `BCryptPasswordEncoder`. Re-running with the same environment variables is idempotent and skips creation if the user already exists.

## Running and Testing

Run the application locally:

```bash
APP_FIRST_OWNER_USERNAME=owner \
APP_FIRST_OWNER_PASSWORD=your-strong-password \
APP_FIRST_OWNER_DISPLAY_NAME="Shop Owner" \
./mvnw spring-boot:run
```

Run the test suite (uses `src/test/resources/application-test.yml` targeting `bagade_test` by default):

```bash
./mvnw test
```

Build the executable JAR:

```bash
./mvnw clean package
```

# Quick start

Build the fat jar, start one node with durability on disk, and run a few SQL statements. A two-node HA pair is at the end of the page.

## Requirements

- JDK **25** with `--enable-preview`, the same as the starter
- Maven, module `grid-sql-server-starter`
- Free port **15432** for SQL; for HA also **15433**, **5615**, and **5616**

## Build and run one durable node

```powershell
mvn -o -pl grid-sql-server-starter -am package -DskipTests
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=capacity
```

The `capacity` profile listens for SQL on **15432** with `fsync: true` and replication off. From an IDE you can run `org.genfork.grid.sql.SqlServerMain` with the same profile.

Readiness: `http://127.0.0.1:7777/actuator/health/readiness` when Actuator listens on 7777.

## First client

URL:

```
grid://@127.0.0.1:15432/public
```

Use `RemoteConnectionFactory` from `grid-sql-client`, or the [SQL CLI](../tools/sql-cli.md) for one-off statements.

```sql
CREATE TABLE IF NOT EXISTS demo (id BIGINT PRIMARY KEY, name VARCHAR);
UPSERT INTO demo (id, name) VALUES (1, 'hello');
SELECT id, name FROM demo WHERE id = 1;
```

SQL is parsed by ANTLR only (`SimplifiedSql.g4`). DDL inside an open transaction is rejected, so change schema in autocommit.

## Two-node HA

```powershell
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=primary
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=replica
```

Ports: SQL **15432** / **15433**, replication **5615** / **5616**. Multi-host URLs and writer pinning: [connect clients](connect-clients.md).

## Limits

- The replication port is not a SQL port: a client on **5615** will not speak SQL.
- Every process needs its own `dataDir`; a shared network volume for the cluster is unsupported.
- Do not quote `fsync: false` numbers as a ceiling.

## If the node did not come up

| Symptom | Check |
|---------|-------|
| Port in use, bind failed | Another process on **15432**, or Actuator on **7777** |
| Readiness DOWN | With replication, wait for ORCHID sync; otherwise read the startup logs |
| Client reports `bad frameLen` | You connected to a replication port (**5615** / **5616**) instead of SQL |
| AUTH or connect rejected | `capacity` starts with no users — use a URL without `user:pass`, or create a user first |

## Next

1. [Start a cluster](start-cluster.md) — the `primary` and `replica` profiles
2. [Java client](../develop/java-client.md) — transactions, PREPARE, streaming results
3. [Production checklist](production-checklist.md) — what to settle before go-live
4. [Compose stands](../configure-and-operate/operations/deploy-compose.md) — ready-made topologies

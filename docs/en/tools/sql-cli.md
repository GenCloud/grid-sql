# SQL CLI

`org.genfork.grid.sql.cli.SqlCli` in **`grid-sql-client`** is an interactive shell that talks to a running node over the same stack as an application: `grid://` and `RemoteConnectionFactory`, never JDBC. DDL, transactions and prepared statements therefore behave exactly as they will in client code.

Use it for one-off DDL/DML, quick schema checks and manual TX / PREPARE debugging. Throughput and p95 belong to [JMeter](jmeter-load-slo.md).

## When to use it

| Task | CLI | Use instead |
|------|-----|-------------|
| One-off `CREATE TABLE` or `SELECT` check | Yes | — |
| Manual BEGIN / COMMIT / SAVEPOINT | Yes | — |
| PREPARE / EXECUTE / DEALLOCATE | Yes (`\prepare` …) | — |
| TPS and p95 measurement | No | [JMeter](jmeter-load-slo.md) |
| Schema browser in an IDE | No | [JDBC client](../develop/jdbc-tooling.md) |

`.block()` on the CLI thread is expected: `main` is an application synchronization boundary, not library API.

## Scope

The CLI speaks `grid://` (Grid frames), not JDBC. There is no `\d` meta-command set and no interactive command history. What you do get is the same URL and AUTH as the application — a fast way to check DDL and transaction behaviour before writing client code.

## Launch

```powershell
mvn -o -pl grid-sql-client -am package -DskipTests
java --enable-preview -cp grid-sql-client/target/classes;... org.genfork.grid.sql.cli.SqlCli -h 127.0.0.1 -p 15432
```

Connection arguments come in either form:

| Form | Example |
|------|---------|
| URL | `grid://user:pass@127.0.0.1:15432/public` |
| Flags | `-h` / `--host`, `-p` / `--port`, `-u` / `--user`, `-P` / `--password` |

Defaults are host `127.0.0.1` and SQL port **15432**; a local replica serves reads on **15433** when the replica profile is up. Ports **5615** / **5616** carry replication, not SQL — pointing the CLI there yields a bad frame.

## Common errors

Statement errors print to **stderr** and the interactive session continues (`quit` / `exit` / EOF ends the process). A failed connect aborts `main` with an exception (non-zero process status from the JVM). There is no per-statement process exit code for SQL failures.

| Symptom | Cause |
|---------|-------|
| `bad frameLen` | CLI pointed at a replication port (**5615** / **5616**) |
| AUTH rejected | Wrong user or password, or the node has users while the URL carries none |
| Schema or table not found | Wrong schema in the URL (usually `/public` is meant) or DDL not run yet |
| Connect timeout | Node not listening on SQL, wrong host/port, or readiness still DOWN |
| `maxTxContexts=8 exhausted` | More than eight open sessions on one TCP — use another connection or close idle TX |

## Session commands

One line is one statement (Simplified SQL). An empty line, `quit` or `exit` leaves the shell.

| Input | Behaviour |
|-------|-----------|
| `BEGIN` / `COMMIT` / `ROLLBACK` | Single current `TxContext` on the connection |
| `SAVEPOINT name` / `ROLLBACK TO name` | Savepoint inside the open transaction |
| Plain SQL | Autocommit when no transaction is open, otherwise inside it |
| `\prepare name AS …` | Named `PreparedHandle` |
| `\execute name …` | Run a prepared statement |
| `\deallocate name` | Drop a prepared statement |

## Limits

- One line at a time: no multi-line editor and no interactive command history.
- No EXPLAIN formatter beyond the text the server returns.
- Not a JDBC replacement for applications or DBeaver — that is `org.genfork.grid.jdbc` in `grid-sql-client`.
- Does not start a node: SQL TCP must already listen (`grid.sql-server.enabled`).

## Related

- Bring a node up: [quick start](../getting-started/quick-start.md), [start a cluster](../getting-started/start-cluster.md)
- URL and writer pin: [connect clients](../getting-started/connect-clients.md)
- Port configuration: [SQL server](../configure-and-operate/configuration/sql-server.md)
- Application API: [Java client](../develop/java-client.md), [transactions](../develop/transactions.md)

Russian: [sql-cli.md](../../ru/tools/sql-cli.md).

# examples-jooq

Thin jOOQ drop-in over multiplex `GridDataSource` (`SyncConnectionFactory.shared`, floor ≥10 TCP × `maxTxContexts`).

Hikari `maximumPoolSize=N` sockets break Grid multiplex — use `GridDataSource`. Hikari reject lives in JDBC (`GridHikariBridgeGuard`), not in `grid-jooq`.

```text
mvn -pl examples/examples-jooq -am package
java -cp ... org.genfork.grid.examples.jooq.JooqExample
```

Requires a live Grid SQL TCP endpoint (`ExampleSupport.jdbcUrl()`).
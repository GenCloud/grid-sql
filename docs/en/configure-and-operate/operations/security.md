# Security and privileges

How users, frame AUTH, and SQL GRANT work. Operator page: what to enable before admitting applications, and what the product does **not** do by itself.

DDL syntax: [DDL](../../sql/ddl.md). Port and readiness: [SQL server](../configuration/sql-server.md).

## Model

| Layer | Role |
|-------|------|
| AUTH frame | Channel identity after connect (login/password from the URL) |
| `privileges.meta` catalog | Users, roles, GRANT on schemas/tables |
| ORCHID / OpLog | Write **admission and durability** — not AUTH |

AUTH is **not** 2PC and does not decide whether a row may commit. Durability: [durability](../configuration/durability.md).

## Empty catalog and first admin

| State | Behaviour |
|-------|-----------|
| No users + configured `grid.sql-server.user`/`password` (defaults `grid`/`grid`) | Runtime seeds that user as **administrator** with absolute rights on all operations and all schemas/tables |
| No users + both credentials blank | Open-auth (passwordless) until the first `CREATE USER` |
| Users exist | URL needs credentials; frames without AUTH are rejected |

Typical connect after bootstrap:

```text
jdbc:grid://grid:grid@127.0.0.1:15432/public
```

Then as master (or after `ALTER USER`):

```sql
CREATE USER app PASSWORD '…';
GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA public TO app;
```

Password change: `ALTER USER … PASSWORD` (PBKDF2). Do not store production passwords in a shared repository config.

## What is checked

- Table privileges, including JOIN sides.
- Set: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `DDL`.
- Roles: `CREATE ROLE` / `DROP ROLE` / `GRANT ROLE … TO user`.

The client (reactive `grid://` or JDBC `jdbc:grid://`) passes the same credentials in the URL authority.

## Where the catalog lives

File `privileges.meta` is under the SQL data directory: `{grid.sql.data-dir}/catalog/privileges.meta` (default next to `./data-…/catalog`). Per-node local file, not shared NFS.

**No cluster sync.** Replication does **not** ship `privileges.meta`. `CREATE USER` / `GRANT` / password rotation must be applied on **every** node that apps may hit (or bootstrap identical catalogs and copy the file as part of your node procedure). A user that exists only on the writer will AUTH-fail on a replica SQL port.

## DDL limits

| Expectation | Fact |
|-------------|------|
| `REVOKE … FROM user` | Yes — drops privileges from a user |
| `REVOKE ROLE` / detach role membership with a dedicated command | No — drop membership via `DROP ROLE` |
| Privileges on a role (`GRANT … TO ROLE`) | Yes; checked through user membership |

## Not in the product

| Expectation | Fact |
|-------------|------|
| TLS / mTLS on the SQL port out of the box | No — terminate TLS outside if needed (proxy/LB) |
| Separate IdP / OAuth | No — local user catalog |
| Row-level security | No |
| AUTH as commit arbiter | No |

## Network and TLS before bootstrap

| Step | Action |
|------|--------|
| 1 | SQL port (**15432**) only to trusted networks / LB; replication (**5615**) — separate node-to-node path |
| 2 | While the catalog is empty, anyone who reaches the port can create admin — restrict access until the first `CREATE USER` |
| 3 | No TLS on the SQL port in product — terminate TLS on a proxy/LB in front of the node if needed |
| 4 | After the first user, apps get credentials in the URL (`grid://` / `jdbc:grid://`) |

Operator checklist: firewall → first admin → `GRANT` for the app → readiness UP → traffic.

## Credential rotation

After AUTH is on (catalog has users), rotate with `ALTER USER … PASSWORD`, then roll application URLs / secrets. Do not leave bootstrap passwordless access: once the first admin exists, frames without AUTH are rejected. Keep `privileges.meta` on local disk per node; back it up with the node `dataDir` policy, not a shared NFS share.

**Cadence.** Pick an interval that matches your secret policy (for example after personnel change, or on a fixed schedule). Rotate admin first in a maintenance window, then app users; keep old passwords only until all clients have the new URL secret. Verify: connect with the new password succeeds; connect with the old password fails.

### Revoke and least privilege

```sql
-- narrow an app that only needs reads
REVOKE INSERT, UPDATE, DELETE ON SCHEMA public FROM app;
GRANT SELECT ON SCHEMA public TO app;
```

After `REVOKE`, open sessions may still hold old rights until reconnect — roll or reconnect clients.

### TLS outside the node

There is no product TLS on the SQL port. Put a reverse proxy or load balancer in front that terminates TLS and forwards plaintext only on a private network to **15432**. Replication (**5615**) stays on a separate node-to-node path — do not publish it to the public internet.

## Operational rules

1. Until users exist, anyone who reaches the port can create admin — restrict the network.
2. After the first user, applications without URL credentials will not connect.
3. Do not confuse the SQL port (**15432**) with replication (**5615**).
4. Readiness UP does not replace AUTH: the channel can be ready while a frame without login is rejected.

**Related:** [failures](failures.md), [SQL server](../configuration/sql-server.md), [connect clients](../../getting-started/connect-clients.md).
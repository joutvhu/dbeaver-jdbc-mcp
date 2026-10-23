# DBeaver JDBC MCP Server

A high-performance, universal Model Context Protocol (MCP) server built with the **official Model Context Protocol Java SDK (`io.modelcontextprotocol.sdk:mcp`)** that connects AI assistants (Kiro, Claude Desktop, Cursor, etc.) directly to all database connections configured in **DBeaver**.

Unlike traditional MCP servers that require separate Python drivers, C++ build tools, or ODBC libraries for each database type, **dbeaver-jdbc-mcp** runs natively on Java and **reuses the exact JDBC drivers already downloaded and managed by DBeaver**.

---

## 🌟 Key Highlights

- **Official Java SDK**: Powered by `io.modelcontextprotocol.sdk:mcp-core` (Version 2.0.1) and Project Reactor, compliant with MCP specification revisions `2024-11-05` and `2025-11-25`.
- **100% Database Coverage**: Supports any database that DBeaver can connect to (PostgreSQL, MySQL, SQL Server, Oracle, SQLite, Snowflake, ClickHouse, DuckDB, Trino, DB2, Informix, etc.).
- **Zero Driver Setup**: Automatically scans and dynamically loads JDBC `.jar` drivers from `%APPDATA%\DBeaverData\drivers\`.
- **Zero Runtime Installation**: Automatically detects and uses DBeaver's bundled OpenJDK JRE — **no need to install Python, Node.js, C++ compilers, or a separate Java runtime**.
- **Cross-Platform**: Seamlessly works on Windows, macOS, and Linux.

---

## 🚀 Easy Ways to Install & Run

You can choose any of the 3 standard options below to configure `dbeaver-jdbc-mcp` in your AI client (e.g. `mcp.json` in Kiro, Claude Desktop, or Cursor):

### Option 1: Run directly via `npx` (Recommended - Zero Installation)
No manual download required. Just add to your `mcp.json`:

```json
{
  "mcpServers": {
    "dbeaver": {
      "command": "npx",
      "args": ["-y", "dbeaver-jdbc-mcp"]
    }
  }
}
```

---

### Option 2: Run via `jbang` (Maven Central)
If you have `jbang` installed:

```json
{
  "mcpServers": {
    "dbeaver": {
      "command": "jbang",
      "args": ["run", "io.github.joutvhu:dbeaver-jdbc-mcp:1.0.0"]
    }
  }
}
```

---

### Option 3: Download Portable ZIP from GitHub Releases
1. Download `dbeaver-jdbc-mcp-x.x.x-portable.zip` from the **GitHub Releases** page.
2. Extract it anywhere on your machine.
3. Configure your `mcp.json`:

**On Windows:**
```json
{
  "mcpServers": {
    "dbeaver": {
      "command": "C:\\path\\to\\dbeaver-jdbc-mcp\\dbeaver-jdbc-mcp.cmd"
    }
  }
}
```

**On macOS / Linux:**
```json
{
  "mcpServers": {
    "dbeaver": {
      "command": "/path/to/dbeaver-jdbc-mcp/dbeaver-jdbc-mcp.sh"
    }
  }
}
```
*(The `.cmd` and `.sh` scripts automatically detect DBeaver's installation path via Windows Registry, AppData, macOS `/Applications`, or Linux `/usr/share/dbeaver`).*

---

## 🛠 Build from Source

Requirements: JDK 17+ (No need to install Maven, as the Maven Wrapper is included):

**On Windows:**
```powershell
.\mvnw.cmd clean package
```

**On macOS / Linux:**
```bash
./mvnw clean package
```
This generates the standalone Fat JAR at `dbeaver-jdbc-mcp.jar` (and in `target/dbeaver-jdbc-mcp.jar`).

### 🧪 Publish & Test Locally (Install to `npm -g`)

To test the package locally as a real global CLI tool:

```bash
npm run publish:local
```

Script automatically builds the Fat JAR, packages it, and installs it directly into `npm -g`. You can then run `dbeaver-jdbc-mcp` from anywhere or configure `"command": "dbeaver-jdbc-mcp"` in your `mcp.json`. (To uninstall: `npm uninstall -g dbeaver-jdbc-mcp`).

---

## 🛠 Available Tools

| Tool | Description | Parameters |
| :--- | :--- | :--- |
| `list_connections` | List all DBeaver database connections (passwords safely redacted). | `details` (boolean, optional) |
| `test_connection` | Test connectivity, measuring latency and server version. | `connection_id` (string, required) |
| `list_tables` | List tables and views across schemas using standard JDBC metadata. | `connection_id` (string), `schema` (optional), `include_views` (optional) |
| `describe_table` | Describe table columns (types, sizes, nullability, defaults, primary keys). | `connection_id` (string), `table_name` (string) |
| `query` | Execute a safe, read-only SQL query (`SELECT`, `EXPLAIN`, `SHOW`, `DESCRIBE`). | `connection_id` (string), `sql` (string), `max_rows` (optional, default 1000) |
| `export_query` | Execute a read-only query and export results as `json` or `csv`. | `connection_id` (string), `sql` (string), `format` ("json" \| "csv"), `max_rows` (optional) |
| `execute` | Execute safe data modification (`INSERT`, `UPDATE with WHERE`, `DELETE with WHERE`, `MERGE`). Blocked when `READ_ONLY=true`. | `connection_id` (string), `sql` (string) |
| `db_stats` | Retrieve database statistics (table count, server version, ping). | `connection_id` (string) |
| `debug_info` | Inspect DBeaver workspace path, detected driver JARs, and runtime status. | None |

---

## 🔒 Safety & Security Rules

1. **Read-Only by default**: Write operations (`execute` tool) are disabled unless `READ_ONLY=false` is explicitly set in your environment.
2. **Bulk Modification Prevention**: `UPDATE` and `DELETE` statements **must** include a `WHERE` clause.
3. **DDL & Admin Protection**: `DROP`, `TRUNCATE`, `ALTER`, `GRANT`, `REVOKE`, `CREATE USER`, and `SHUTDOWN` are strictly blocked.
4. **Credential Protection**: Passwords and secrets are never returned in `list_connections` responses.

---

## ⚙️ Environment Variables

All settings can be customized using environment variables:

| Variable | Description | Default |
| :--- | :--- | :--- |
| `READ_ONLY` | Block data modifications (`true`/`false`). When `true`, `execute` is disabled. | `true` |
| `DBEAVER_AES_KEY` | AES encryption key for decrypting `credentials-config.json` (hex string or text). | `babb4a9f774ab853c96c2d653dfe544a` |
| `DBEAVER_AES_IV` | AES Initialization Vector for credentials decryption (hex string or text). | 16 zero bytes (`0x00...`) |
| `DBEAVER_WORKSPACE` | Custom path to DBeaver workspace directory. | Auto-detected (`%APPDATA%\DBeaverData\workspace6`) |
| `DBEAVER_DRIVERS_PATH` | Path to additional directory containing JDBC `.jar` drivers. | Auto-detected (`%APPDATA%\DBeaverData\drivers`) |
| `DBEAVER_QUERY_TIMEOUT_SECONDS` | Query execution timeout in seconds. | `0` (no timeout) |
| `DBEAVER_MAX_ROWS_LIMIT` | Hard ceiling for maximum rows returned per query. | `10000` |
| `DBEAVER_DEBUG` | Enable verbose diagnostic logging to stderr (`true`/`false`). | `false` |

---

## 📦 Automated Release Pipeline

This project includes a continuous delivery pipeline in `.github/workflows/release.yml`. Whenever you create and publish a new **Release** on GitHub (e.g. tagging `v1.0.0` and clicking **Publish release**):
1. Runs tests and builds the official Fat JAR.
2. Automatically uploads `dbeaver-jdbc-mcp.jar`, portable zip, `.cmd`, and `.sh` assets to that GitHub Release.
3. Automatically publishes the package to the **NPM Registry** (`npx dbeaver-jdbc-mcp`).
4. Signs and deploys artifacts to **Maven Central** (when Sonatype / GPG secrets are configured).

---

## 📄 License

This project is open-sourced under the [Apache-2.0 License](LICENSE).

# Security and operational boundaries

This is a local diagnostic prototype, **not a hardened production management gateway**. It is read-only for monitored process execution, but metrics, snapshot metadata and diagnostic outputs can still reveal operational details.

- Binds only to `127.0.0.1` by default. Do not expose raw HTTP to the internet or a shared network. Use SSH forwarding for local access or an organisation-approved HTTPS reverse proxy with authentication, audit and per-user permissions.
- Non-loopback bindings require a bearer token with at least 24 characters; the demo UI does not handle bearer-token authentication and is intended for loopback or a trusted proxy. Prefer `QUANTLAB_TOKEN` in the launch environment instead of the `--token` argument, which may be visible in shell history/process metadata; avoid this mode outside controlled testing.
- `POST /api/watch` can register any visible local PID, so **do not deploy this endpoint without production access controls**. An integration must map requests to the existing daemon's authorised process registry rather than allow arbitrary PIDs.
- `jcmd` attachment can trigger safepoints, interfere with timing or expose sensitive text. It is disabled by default; enables only three allowlisted commands and intentionally does not support heap dumps, arbitrary commands or code execution.
- No command lines, credentials, environment variables, JVM property values or heap dumps are collected into automatic snapshots.
- Data directories should be owned by the daemon's service account with restrictive OS permissions. The prototype relies on the process umask; configure a restrictive umask before launching.
- Snapshot comparison is observational only. Rules are not root-cause proofs, and should not automatically trigger remedial actions.
- The local HTTP server is not protected by a full production audit/RBAC/quota layer. Before production use, integrate with the existing gateway security controls, add request budgets, redact diagnostic output, and security-test every exposed endpoint.

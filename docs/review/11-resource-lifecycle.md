# Resource lifecycle — cross-cutting pass

## Verdict

The connector's own release logic is mostly sound: `dispose()` closes the connection manager,
`ConnectionManager.close()` walks every pool and every `ServerDefinition` with per-connection
error handling so one failure cannot skip the rest, and the three mutating operations
(`modify`, `deleteAttempt`, `ldapRenameAttempt`) return their connection from a `finally`.
No threads, executors, temp files or classpath streams are leaked — the only two `InputStream`
sites (`SystemSchemaLoader`, `EDirTestSupport`) use try-with-resources correctly, as does the
TCP probe socket. What is broken is the shared cursor-closing helper: `LdapUtil.closeDoneCursor`
throws *before* it closes, so on a referral or an LDAP error mid-cursor the cursor is never
closed and no ABANDON is sent, on a connection that midPoint keeps alive for months. Because
eDirectory defaults `uidAttribute` to `GUID`, that helper sits on the `resolveDn` path of every
update, rename and delete. Beyond that, the remaining gaps all share one shape: connections are
acquired outside a `try`, so every explicit `returnConnection()` call is skippable. That is
harmless for pooled connections by design, but `runAsStrategy=bind` turns each of those sites
into a leaked socket, TLS session and MINA processor thread per failed operation.

## Findings

`blocker | src/main/java/com/evolveum/polygon/connector/ldap/LdapUtil.java:444 | closeDoneCursor() throws ConnectorException before ever calling cursor.close(), so a cursor that is not DONE is leaked instead of closed — the SearchFuture stays registered in the LdapNetworkConnection future map and the server keeps queueing responses into it; both callers invoke it from a finally, so in AbstractLdapConnector.searchSingleEntry:2187 the throw also pre-empts returnConnection() at :2189 and aborts the ReconnectException retry loop, and the caller sees "indicates bug in LDAP connector" instead of the real referral/LDAP error. Reachable on every update, rename and delete, because eDirectory defaults uidAttribute to GUID so resolveDn() -> searchSingleEntry() runs each time, and searchSingleEntry never calls ignoreReferrals() on its SearchRequest so a subordinate partition reference surfaces as CursorLdapReferralException. | Close first, complain second: call cursor.close() unconditionally inside the try, and downgrade the not-DONE case to a LOG.warn (or throw only after the close has run).`

`major | src/main/java/com/evolveum/polygon/connector/ldap/connection/ConnectionManager.java:133 | The connection returned by pool.getConnection(options) is dropped on the floor when the opportunistic root DSE fetch at :139 fails — the caller never receives the reference, so it can never be returned. With runAsStrategy=bind and a runAsUser in the options this is a freshly created runAs connection, and since rootDse stays null until one fetch succeeds, a runAs user that cannot read the root DSE leaks one connection plus its MINA processor thread on every single operation. The reconnect branch at :142 leaks the same way: getConnectionReconnect cannot find a ServerDefinition for a runAs connection and throws IllegalStateException. | Wrap the root DSE block in try/catch and call returnConnection(connection) before rethrowing; better, only fetch the root DSE opportunistically on a pooled connection (skip it when needsSpecialConnection applies).`

`major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:1063 | create() acquires the connection outside any try and then releases it at five separate return/throw points (:1075, :1085, :1093, :1112, and via searchSingleEntry at :1119); the association path at :1105-1113 is not one of them — resolveDn() throwing UnknownUidException or updateAssociationsAttempt() throwing InvalidAttributeValueException skips :1112 entirely. Under runAsStrategy=bind that leaks the runAs connection on every create whose membership update fails. This is the only mutating operation that does not use the finally pattern that modify(), deleteAttempt() and ldapRenameAttempt() already use. | Wrap the body from :1063 onward in try/finally with a single connectionManager.returnConnection(connection), and delete the five scattered calls.`

`major | src/main/java/com/evolveum/polygon/connector/ldap/search/SearchStrategy.java:272 | connect() stores the connection in a field and none of the three strategies wrap their search() body in try/finally, so returnConnection() (DefaultSearchStrategy:181, SimplePagedResultsSearchStrategy:234, VlvSearchStrategy:338) is skipped whenever anything unplanned escapes — a caller-supplied ResultsHandler that throws, or schemaTranslator.toConnIdObject() failing on a malformed entry inside handleResult():226. midPoint does throw out of its handler (search abort, object-count limits), so under runAsStrategy=bind every aborted search leaks its runAs connection. This is separate from the already-known missing cursor try/finally: the resources and the fixes are different. | Put try/finally around the whole search() body in each strategy with returnConnection() in the finally (returnConnection is idempotent, so the existing explicit calls can stay or go).`

`minor | src/main/java/com/evolveum/polygon/connector/ldap/sync/SyncStrategy.java:165 | searchSingleEntry() opens a SearchCursor inside the try and only closes it on the single success path at :177; the "returned more than one entry" IllegalStateException at :172 and both catch blocks (:178, :180) leave it open. Currently unreachable in this fork — fetchEntry() and fetchEntryByUid() have no callers since the OpenDJ/AD strategies were stripped — but both methods are public and are the documented extension point for new sync strategies. | Either delete fetchEntry/fetchEntryByUid/searchSingleEntry as dead code, or move the cursor into try-with-resources / a finally that closes it.`

`minor | src/main/java/com/evolveum/polygon/connector/ldap/search/SearchStrategy.java:182 | executeSearch() returns null on LdapReferralException after already calling returnConnection(), and all three call sites (DefaultSearchStrategy:87, SimplePagedResultsSearchStrategy:111, VlvSearchStrategy:154) dereference the result immediately. The resulting NPE escapes search() past the trailing returnConnection(), and in finishSearch() it is then masked by a second NPE from closeDoneCursor(null) in the finally at :321 — discarding a search whose results had already been handled. The author clearly believed the branch reachable; if it is, it fails badly. | Have executeSearch() throw a typed "referral, no results" signal, or make every caller check for null and break out of the loop cleanly.`

`minor | src/test/java/com/evolveum/polygon/connector/ldap/TestEDirectory.java:96 | Neither live suite has an @AfterClass, so the ConnectorFacade created here (and at TestMidPointIntegration.java:144) is never disposed: AbstractLdapConnector.dispose() — the single place the connector releases sockets and MINA threads — has zero test coverage, and each suite leaves a bound eDirectory connection open for the remainder of the surefire JVM. A regression in ConnectionManager.close() would not be caught by anything in this repository. | Add @AfterClass calling ConnectorFacadeFactory.getInstance().dispose() to both suites; that also gives the close path a smoke test.`

## Checked and clean

- **`SystemSchemaLoader`** — both classpath read sites (`:83` nested `InputStream`/`LdifReader`,
  `:133` combined try-with-resources) close correctly even when `reader.next()` throws. The
  static `RESOURCE_MAP` is computed once per classloader and holds no open handles. A fresh
  `SystemSchemaLoader` is built on every `initializeSchemaManager()` call, which re-reads the
  LDIFs — wasteful, not leaky.
- **`AbstractSchemaTranslator`** — `translateSchema` / `prepareConnIdSchema` acquire no
  connection and open no cursor; they only read the already-built `SchemaManager` and ask
  `ConnectionManager` for cached supported controls.
- **`AbstractLdapConnector.initializeSchemaManager():339`** — takes a connection and never
  returns it, but passes `options == null`, so `needsSpecialConnection` is false and it is
  always a pooled connection. Correct under the stated pooling design.
- **`AbstractLdapConnector.dispose()`** — closes the connection manager before nulling it, so a
  second `dispose()` is a no-op; nulling `configuration` first is safe because
  `ServerConnectionPool` holds its own reference for the `isUseUnbind()` check during close.
  The fields it leaves set (`syncStrategy`, `connectionLog`, `errorHandler`) hold no OS
  resources.
- **`ConnectionManager.close()` / `ServerConnectionPool.close()` / `closeServerConnection()`** —
  iterate every pool and every server, catch `IOException` per connection so one failure cannot
  skip the rest, and clear `serverDef.setConnection(null)` even when `close()` throws, leaving
  no stale reference behind.
- **`ServerConnectionPool.connectConnection():494`** — closes the half-built connection when
  `connect()` throws, and logs the close failure without masking the original error.
- **`ConnectionManager.test()`** — the connections it opens in `TEST_MODE_FULL` are all stored
  in their `ServerDefinition`, so a later `close()` reaches them; a mid-loop failure leaves the
  earlier ones tracked, not orphaned.
- **`modify():1699`, `deleteAttempt():2025`, `ldapRenameAttempt():1293`** — connection returned
  from a `finally`. This is the pattern the sites above are missing.
- **`EDirTestSupport`** — `loadConfigFile():111` and `edirReachable():197` both use
  try-with-resources for the `InputStream` and the probe `Socket`.
- **No threads, executors, files or readers** are created anywhere under `src/main/java`; the
  only OS resources the connector owns are `LdapNetworkConnection` (and the MINA processor it
  brings with it) and the cursors opened on it.
- **`ConnectionManager.brutalSearch()` / `getRandomConnection()`** and
  `ServerConnectionPool.brutalSearch()` / `getRandomConnection()` have no callers — dead code,
  no leak.

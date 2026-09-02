# 13 — Concurrency & mutable state

Cross-cutting pass. Whole repo (`src/main/java`, `src/test/java`). Verified against the ConnId
jars this build actually resolves: `net.tirasa.connid:connector-framework:1.6.0.0-RC1` and
`connector-framework-internal:1.6.0.0-RC1` (sources jars from the local repository).

## 1. The execution model, established from the framework, not assumed

### What `PoolableConnector` actually guarantees

`PoolableConnector` (`org.identityconnectors.framework.spi.PoolableConnector`) is a marker with a
single method, `checkAlive()`. Its javadoc says nothing about threading. The guarantee does not
come from the interface — it comes from three concrete mechanisms in
`connector-framework-internal`:

1. **`LocalConnectorInfoImpl.isConnectorPoolingSupported()`** returns
   `PoolableConnector.class.isAssignableFrom(connectorClass)`. It is derived from the class, not
   from configuration, so a deployer (midPoint included) cannot switch pooling off for
   `EDirectoryLdapConnector`. The pooled path is the only path.

2. **`ConnectorAPIOperationRunnerProxy.invoke()`** brackets *one whole API operation* with
   `pool.borrowObject()` … `poolEntry.close()` in a `finally`. `SearchImpl` and `SyncImpl` are
   plain `ConnectorAPIOperationRunner`s that call `executeQuery(...)` / `sync(...)` synchronously
   on the calling thread; the framework spawns no threads and hands off to no executor. So every
   `ResultsHandler` / `SyncResultsHandler` callback runs inside the borrow window, on the
   borrowing thread.

3. **`ObjectPool`** hands out exclusive ownership. Idle instances live in a
   `ConcurrentLinkedQueue`; `borrowIdleObject()` takes them with `poll()`, and the eviction path
   in `returnObject()` takes them with `remove(entry)` and only disposes when `remove` returned
   `true`. Both are atomic against each other on a `ConcurrentLinkedQueue`, so an instance cannot
   be simultaneously borrowed by one thread and disposed by another. `makeObject()` is guarded by
   a semaphore (`totalPermit`, default `maxObjects = 10`); on exhaustion `borrowObjectNoTest()`
   waits and then throws `ConnectorException("TimeOut")` — it never duplicates an instance.

So: **midPoint's connector pool cannot hand the same connector instance to two threads at once.**
Concurrency against one resource is expressed as up to `maxObjects` *distinct* connector
instances, each single-threaded for the duration of one API operation. `checkAlive()` is called
from `ObjectPool.borrowObject()` on an entry already removed from the idle queue, i.e. by the
owning thread. `dispose()` is only ever called on an entry that has been removed from the idle
queue (`disposeAllObjects()` and `shutdown()` explicitly leave active objects alone). There is no
lifecycle callback that races an in-flight operation.

### Is the configuration object shared between instances?

This is the part that would break everything if it went the other way, and it is the part the
per-unit reviewer did not check. `ConnectorPoolManager.ConnectorPoolHandler.makeObject()`
branches on `localConnectorInfo.isConfigurationStateless()`, which is
`!StatefulConfiguration.class.isAssignableFrom(connectorConfigurationClass)`.

`AbstractLdapConfiguration extends AbstractConfiguration`, and `AbstractConfiguration implements
Configuration` only — **not** `StatefulConfiguration`. Therefore the stateless branch is taken and
every pooled instance gets its own bean from `JavaClassProperties.createBean(...)`, whose
`mergeIntoBean2` does `value = SerializerUtil.cloneObject(value)` before each setter call
(comment in ConnId: *"some value types such as arrays are mutable. make sure the config object has
its own copy"*). So each connector instance owns a private configuration bean **and private
copies of every array property**, including `memberOfAllowedValues`, `servers`,
`operationalAttributes`.

The only `Configuration` instance that *is* shared across threads is the one lazily created in
`OperationalContext.getConfiguration()` (correctly double-checked behind `synchronized` on a
`volatile` field). ConnId uses it for `ValidateApiOp` — `ValidateImpl.validate()` calls
`getOperationalContext().getConfiguration().validate()`. `AbstractLdapConfiguration.validate()`
(line 1244) reads `host`, `port`, `baseContext` and throws; it mutates nothing. Concurrent
`validate()` calls on that shared bean are therefore harmless. That bean is never handed to a
connector instance, so `recompute()` never runs on it.

### Verdict on the per-unit reviewer's conclusion

**The conclusion is right. The stated reason is incomplete on all three of its clauses.**

- *"because the connector is a `PoolableConnector`"* — being poolable is not itself a safety
  property; it is `ObjectPool`'s exclusive borrow plus the borrow-for-the-whole-operation shape of
  `ConnectorAPIOperationRunnerProxy` that make it one. If ConnId had chosen a
  borrow-per-method-call model, the streaming search handler would have been unsafe.
- *"`ConnectionManager` is per-instance"* — true (`init()` line 127 constructs a fresh one per
  connector instance), but the load-bearing fact is that the **configuration bean** is per-instance
  too, which follows from `AbstractLdapConfiguration` not implementing `StatefulConfiguration`.
  That is a one-word change away from being false, and nothing in the code says so.
- *"there is no static state"* — there is. `AbstractSchemaTranslator.SYNTAX_MAP` (line 76) and
  `STRING_ATTRIBUTE_NAMES` (line 75) are mutable containers; `SystemSchemaLoader.RESOURCE_MAP`
  (line 61); `EDirTestSupport.FILE_PROPERTIES` (line 64) and `RUN_ID` (line 75). All of them are
  written **only** from static initialisers and read-only afterwards — I checked every write site
  — so JVM class-initialisation safe publication covers them and they are genuinely fine. But the
  claim as stated was not verified.

### The one genuinely multi-threaded surface, which nobody has flagged

`ConnectorBinaryAttributeDetector` is installed on every connection via
`LdapConnectionConfig.setBinaryAttributeDetector()` (`ServerConnectionPool` line 421). The Apache
Directory API calls it from `LdapMessageContainer.isBinary(String)` — that is the ASN.1 decoder,
which under `api-ldap-net-mina` runs on the **MINA I/O processor thread**, not the ConnId
operation thread. So `ConnectorBinaryAttributeDetector.isBinary()` →
`AbstractSchemaTranslator.isBinaryAttribute()` genuinely executes off-thread, concurrently with
connector code that is iterating a `SearchCursor`.

I traced what that path touches: `getLdapAttributeName()` (pure string work), the connector's
`SchemaManager` (read-only registry lookups on a manager that is fully built before any search
starts), `STRING_ATTRIBUTE_NAMES` (static, class-init), `isBinarySyntax`/`isStringSyntax`
(constant comparisons). All read-only against effectively-immutable state. The one mutable field
crossing the thread boundary is `ConnectorBinaryAttributeDetector.schemaTranslator`, written
non-`volatile` by `ServerConnectionPool.setSchemaTranslator()` (line 77) on the operation thread.
I could not construct a schedule where a stale read is observable: the field is only written while
the connection is idle (from `initializeSchemaTranslator()`, never mid-operation), and the
operation thread's next MINA write goes through the session's concurrent write queue, which
supplies the happens-before edge before any response is decoded. Per the finding rules that makes
it not a finding — but it is one non-`volatile` keyword away from being one, and the class carries
no comment saying it is touched by another thread. Cheap insurance:
`private volatile AbstractSchemaTranslator<C> schemaTranslator;` in
`ConnectorBinaryAttributeDetector` and `ServerConnectionPool`.

### Things checked and cleared (no finding)

- **`recompute()` idempotency.** `AbstractLdapConfiguration.recompute()` (line 1277) and
  `EDirectoryLdapConfiguration.recompute()` (line 106) are every-branch null-guarded
  (`if (x == null) x = default`) and the 3.3 compatibility block self-disables once
  `globalTimeout` is set. Repeated calls are a no-op. `init()` is the only caller, once per
  instance.
- **Configuration mutated after `validate()`/`recompute()`.** One site exists:
  `AbstractSchemaTranslator.shouldValueBeIncluded()` line 1795 writes
  `configuration.setMemberOfAllowedValues(new String[]{ configuration.getBaseContext() })` during
  a search, into the array that `AbstractLdapConfiguration.getMemberOfAllowedValues()` (line 1158)
  hands out live. This is real, and it is ugly, but it is **not** a concurrency defect and I could
  not build a concrete failure from it: the array is that connector instance's private copy (see
  above), the derivation is idempotent, and the value is read nowhere else. The one crash it can
  produce — `baseContext == null` giving `{null}` and then NPE in
  `.filter(Predicate.not(String::isEmpty))` — throws on the *first* call regardless of the
  write-back, so the write-back is not the cause. Recorded here so the next reviewer does not have
  to re-derive it.
- **Iteration over a collection modified in the same loop.** Scanned every for-each in
  `src/` against its own receiver (including `keySet()`/`values()`/`entrySet()` forms). No hits.
  The three near-misses are all safe: `scrapeOriginalMembershipAttrs` uses `removeIf`
  (`AbstractSchemaTranslator` line 260), `determineAttributesToGet` uses an explicit
  `Iterator.remove()` (line 2319), and `retrieveValuesToReplace` (`AbstractLdapConnector` line
  1609) mutates a list built fresh by `saturateReferenceAttributeValues`, not the caller's delta.
- **Association-set lazy init.** `getObjectAssociationSets()` / `getSubjectAssociationSets()`
  (`AbstractSchemaTranslator` lines 2612/2624) return `null` when `managedAssociationPairs` is
  empty, and several callers dereference the result immediately. Every one of those call sites is
  guarded by `!ArrayUtils.isEmpty(...getManagedAssociationPairs())` (lines 1457, 2325;
  `AbstractLdapConnector` lines 957, 1307), so the NPE is unreachable. Fragile, but not a finding.
- **No threading primitives anywhere in `src/`.** Zero occurrences of `Thread`, `Executor`,
  `Timer`, `ThreadLocal`, `synchronized`, `volatile`, `Atomic*` or `java.util.concurrent`. That is
  consistent with, and appropriate for, the model above.

## 2. Findings

```
major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:1934 | syncStrategy is cached for the life of the pooled instance and pins the SchemaManager + SchemaTranslator captured at first sync (SyncStrategy stores both as final fields, lines 53-54); schema() lines 482-483 and cleanupBeforeTest() lines 216-217 replace those on the connector but leave syncStrategy alone, so a livesync run on an instance that has since served a schema() sees the old schema — an object class added after the refresh makes ModifyTimestampSyncStrategy.sync() line 81 get null from findObjectClassInfo() and throw "No definition for object class", while a search for the same class succeeds | null syncStrategy wherever schemaManager/schemaTranslator are nulled (schema(), cleanupBeforeTest(), dispose())
major | src/main/java/com/evolveum/polygon/connector/ldap/connection/ServerConnectionPool.java:625 | when the runAs bind fails, createSpecialConnectionBind() closes the ServerDefinition's shared pooled connection (closeServerConnection sets serverDef.setConnection(null), line 337) instead of the runAs connection it just opened on line 621 — so one wrong runWithPassword both leaks an open, bound-less LDAP connection that no code path can ever reach again and tears down the connection every other operation on that server is using | close the local `connection` variable in the catch block, and leave serverDef.getConnection() untouched
minor | src/main/java/com/evolveum/polygon/connector/ldap/connection/ConnectionManager.java:424 | lazy init of supportedControls leaves the field null when root DSE carries no supportedControl attribute (ACL-restricted eDirectory), so getSupportedControls() line 404 returns null and AbstractSchemaTranslator.translateSchema() line 149 dies with a bare NullPointerException instead of a diagnosable error; getSupportedControls() also re-runs parseSupportedControls() on every call, re-fetching root DSE each time | set supportedControls to an empty list in the else-less branch so the warning is the whole story and the lazy init actually completes
minor | src/main/java/com/evolveum/polygon/connector/ldap/connection/ConnectionManager.java:401 | derived-capability caches are never invalidated: supportedControls and rootDse (line 61) are populated from whichever server answered first and survive failover, and test() line 335 refreshes rootDse without re-parsing supportedControls; AbstractLdapConnector.usePermissiveModify / useTreeDelete (lines 101-102) are likewise computed once and never reset — with a multi-server config where the primary supports simple paged results and the failover target does not, every paged search after the failover sends an unsupported control to a server the connector still believes supports it | reset supportedControls (and the two Boolean caches) whenever the active server changes and in test()
minor | src/test/java/com/evolveum/polygon/connector/ldap/EDirTestSupport.java:261 | purgePreviousRuns() deletes every cn=test-* object in the configured containers whose name does not contain this JVM's RUN_ID, so two test runs against the same eDirectory (two developers on the shared docker rig, or two CI jobs) have the later run's purge delete the earlier run's fixtures mid-flight, failing it with UnknownUidException; the "no suite purges another's objects" comment on line 75 only holds within one JVM, which the default surefire config happens to give but nothing enforces | scope the purge by age (skip objects whose RUN_ID timestamp is within the last hour) rather than by "not mine"
```

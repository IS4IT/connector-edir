# 99 — Summary

Fourteen reviews: nine per-unit, five cross-cutting. Roughly 13k LOC of Java plus the build and
the test rig. Raw output was **11 blocker-tagged, ~45 major, ~50 minor**; after deduplication and
the skeptic pass below, **9 blockers and 34 majors** stand. (`README.md:37` merges into the
`allowUntrustedSsl` blocker; `AbstractSchemaTranslator:1627` is downgraded to major.)

See `98-upstream.md` for which of these belong upstream in `Evolveum/connector-ldap` rather than
here.

## Verdict

The connector's *structure* is sound — the template-method split, the strategy pattern, the
connection pool's server selection, DN handling, filter escaping and timestamp round-tripping were
all checked and are correct. What is weak is the behaviour on failure paths, and it is weak
consistently: on error the code tends to leak the resource, mask the original exception, and report
the operation as something other than what happened. Three separate reviewers arrived at that shape
from different directions.

The single most consequential class of defect is **failures that reach midPoint as something
benign**. `searchSingleEntry` turns any non-SUCCESS search result into "no such object"; two paging
strategies truncate a result set while reporting it complete; `create()` reports a committed account
as not-found. Each of those ends in the same place: midPoint's picture of the directory diverges
from the directory, and reconciliation "repairs" it by deleting or duplicating accounts.

Second, this fork's own deletions left visible edges. `ReconnectException` is never constructed, so
reconnect-and-retry is dead code. `synchronizationStrategy` still offers three strategies that no
longer exist, and midPoint renders that list as a dropdown. `DiscoverConfigurationOp` was dropped
from the implements clause but its methods remain, kept alive-looking by commented-out `//@Override`.
The only surviving sync strategy cannot emit `DELETE`.

Third, the documentation written for this fork is close to the code, but the inherited documentation
describes the pre-fork connector, and `README.md`'s migration procedure is incomplete in two ways
that would bite a real migration.

## The ten to fix first

Ranked by severity, then by how much of the system the failure touches.

| # | what breaks | where | sev |
|---|---|---|---|
| 1 | `byte[]` attribute values are written to the directory as `[B@1b6d3586`; the "detected binary" fallback is unreachable because `isBinarySyntax` / `!isBinarySyntax` partition the space. Live on eDirectory: Octet List and Replica Pointer are declared `byte[]` by `toConnIdType` but absent from `isBinarySyntax` | `schema/AbstractSchemaTranslator.java:869` + `edirectory/EDirectorySchemaTranslator.java:166` | blocker |
| 2 | any non-SUCCESS `SearchResultDone` is read as "entry not there" → `UnknownUidException` → shadow marked dead → reconciliation re-creates the account. A transient `insufficientAccessRights` or `busy` during update becomes a duplicated account | `AbstractLdapConnector.java:2165` | blocker |
| 3 | `closeDoneCursor` throws *before* `cursor.close()`, so a not-DONE cursor is leaked with no ABANDON, and the throw masks the real exception from two `finally` blocks. On the `resolveDn` path of every update, rename and delete | `LdapUtil.java:444`; masking at `AbstractLdapConnector.java:2187`, `SimplePagedResultsSearchStrategy.java:321` | blocker |
| 4 | every account creation writes the new password in cleartext to the connector log at INFO — `processEntryBeforeCreate` decrypts at :1057, the whole `Entry` is logged at :1065. The modify path guards this; create does not | `OperationLog.java:53` / `AbstractLdapConnector.java:1065` | blocker |
| 5 | every failed bind leaks a socket, TLS session and a MINA processor thread (`closeServerConnection` runs before `setConnection`, so it closes nothing). midPoint retries failed binds. The runAs variant additionally tears down the *shared* pooled connection | `connection/ServerConnectionPool.java:435`, `:625` | blocker |
| 6 | `allowUntrustedSsl` defaults to `true` in this fork, so `NoVerificationTrustManager` is installed for every ssl/starttls connection — bind DN and password go to whoever answers. Separately, hostname verification is never performed even when it is `false` | `AbstractLdapConfiguration.java:99`; `ServerConnectionPool.java:449`, `:455` | blocker |
| 7 | a `referral` result code falls through with no `break`, re-sending the identical search 10 times and re-delivering every entry to midPoint each time, then failing with "Maximum number of attempts exceeded". eDirectory returns referrals routinely for non-local partitions | `search/DefaultSearchStrategy.java:145` | blocker |
| 8 | the test purge treats any `cn=test-*` as a leftover, so pointing the suites at a real directory irrecoverably deletes pre-existing objects that merely share the prefix. Defaults to on | `EDirTestSupport.java:261` | blocker |
| 9 | an empty page with a live cookie ends the search reporting `completeResultSet=true`, so reconciliation treats every unfetched account as deleted | `SimplePagedResultsSearchStrategy.java:218`, `VlvSearchStrategy.java:329` | major |
| 10 | post-decryption attribute values are embedded in the ConnId exception message, which midPoint persists in the shadow operation result and renders in the GUI and audit — surviving log rotation and log-level changes. `isSensitiveAttribute` masks exactly one attribute name | `AbstractLdapConnector.java:1705`, `:1911` | major |

## By theme

**Failures reported as success or as the wrong thing** — 1, 2, 9 above, plus: `create()` throws
`UnknownUidException` for an account it just committed (`:1122`); `connectionLog.success` written
before the result code is checked (`:1080`, `:1687`); `searchSuccess` logged for `REFERRAL` and
`INSUFFICIENT_ACCESS_RIGHTS` (`SearchStrategy.java:208`); `SIZE_LIMIT_EXCEEDED`/`TIME_LIMIT_EXCEEDED`
→ `PermissionDeniedException` and `OTHER` → `ConfigurationException`, both of which turn a routine
eDirectory condition into a fatal or misdiagnosed one (`ErrorHandler.java:154`, `:147`);
`processCreateResult` logging "created successfully" on the failure path only
(`EDirectoryLdapConnector.java:178`).

**Resource leaks** — 3, 5 above, plus: no `try/finally` around the cursor in any of the three paging
strategies, so a throwing midPoint handler leaves the search un-abandoned and the API buffers the
remainder of the result set (`DefaultSearchStrategy.java:104` and siblings);
`ModifyTimestampSyncStrategy.java:171` leaks its `EntryCursor` on every error path;
`ConnectionManager.java:133` drops a connection when the opportunistic root DSE fetch fails;
`create()` has five release points and misses the association path (`:1063`); `SearchStrategy.java:272`
releases outside `try`. All harmless for pooled connections by design — each becomes a real leak
under `runAsStrategy=bind`.

**Secrets** — 4, 10 above, plus the bind password copied into an unclearable `String` and a never-wiped
`byte[]` (`ServerConnectionPool.java:530`); per-server `bindPassword=` parsed into a
non-confidential `String[]`, so secondary-server passwords sit in cleartext in the midPoint resource
(`ServerDefinition.java:160`); guard-on-one-logger / emit-on-another at `SearchStrategy.java:199`.
**A trap for whoever fixes these:** `SyncStrategy.java:170` reuses `{1}` and therefore leaks nothing
today; renumbering it — the obvious fix, identical in shape to `ConnectionLog.searchError:151` —
would *create* a default-on full entry dump. Drop the argument instead.

**Diagnostics that vanish** — every `ConnectionLog.error*` method gates on `isError()` then emits via
`LOG.info()`, so with the logger at ERROR (the level an operator picks to watch for failures) the
record is dropped: bind, add, modify, delete and search errors leave no trace (`ConnectionLog.java:68`).
`searchError`'s `MessageFormat` indexes are shifted, so a logged search error carries no error text
(`:151`). `OperationLog` has no `Throwable` overload, so stack traces are discarded at seven call
sites. Inverted null branches in all three `logOperationError` overloads (`LdapUtil.java:278`).

**eDirectory semantics** — `isGroupObjectClass` is an exact name match while `isUserObjectClass` walks
superiors, so a derived group class silently loses every reciprocal `groupMembership` and
`equivalentToMe` write (`EDirectorySchemaTranslator.java:133`); `__LOCK_OUT__` is derived from
`loginIntruderResetTime`, which is never added to the search request, so locked accounts read as
unlocked on the path midPoint uses (`:105`); `__ENABLE__` fails open on an empty replace delta
(`EDirectoryLdapConnector.java:94`); the `__ENABLE__` filter and the `__ENABLE__` read disagree about
a missing `loginDisabled`, so a search for enabled accounts omits every account never disabled
(`EDirectoryLdapFilterTranslator.java:56`); reciprocal membership writes have no compensation and run
after the group's `member` was already committed, so a partial failure cannot be repaired by retrying
(`EDirectoryLdapConnector.java:303`).

**Consequences of the fork's deletions** — `ReconnectException` never constructed, so reconnect-and-retry
is dead and `searchSingleEntry`'s loop is effectively an `if`; `synchronizationStrategy.allowedValues`
still offers three deleted strategies and midPoint renders them as a dropdown
(`AbstractLdapConfiguration.java:948`); `DiscoverConfigurationOp` dropped but its methods retained
behind commented-out `//@Override`, so the midPoint wizard's discovery and partial test silently do
nothing; `ModifyTimestampSyncStrategy` hard-wires `CREATE_OR_UPDATE`, so with the other strategies gone
the connector can never report a deletion.

**Documentation** — `README.md:31`: step 2 invalidates every resource's resolved `connectorRef` OID and
nothing in the procedure repairs it. `README.md:37`: a migrated resource that relied on the upstream
default silently stops validating the LDAPS certificate. The migration also omits the
`timeout` → `globalTimeout` rename. `.github/copilot-instructions.md` documents three deleted classes,
a non-existent `setSearchStrategy()`, a `releaseConnection()` that is called `returnConnection`, an
abstract `ErrorHandler` that is concrete, and claims sync emits `DELETE` and that the token is read
from the server — both false. `docker/README.md` understates the healthcheck timeout, omits `rig seed`,
and does not mention that `rig deploy` runs `mvn clean` and `rm -f connector-*.jar`.
`test.properties.example:45` claims the derived-object-class path is tested; `TestMidPointIntegration.java:255`
hardcodes `ri:inetOrgPerson`.

**Tests** — 8 above, plus: the containment check is a string `endsWith` rather than a DN comparison, so
a legal `ou=users, o=data` silently disarms the purge while logging what looks like a scope violation
(`EDirTestSupport.java:245`); the regex JSON reader takes the first match in a window opening 2000
chars before the anchor, so `test000` can false-alarm on a neighbouring connector's fields and
`test010`'s duplicate-bundle guard passes vacuously (`TestMidPointIntegration.java:353`, `:205`);
`test150Unlock` asserts a value a never-locked account reports anyway, so a broken unlock passes
(`TestEDirectory.java:244`); credentials travel as HTTP Basic and `<clearValue>` over the `http://`
URL the example ships (`TestMidPointIntegration.java:129`); neither suite disposes its
`ConnectorFacade`, leaving `dispose()` and `ConnectionManager.close()` with zero coverage.

**Build and rig** — the connector has no display name in midPoint: `@ConnectorClass` asks for
`connector.ldap.edirectory.display`, the catalog defines `connector.ldap.display`, ConnId falls back
to the FQCN. `mvn verify -Pdependencytrack` binds `upload-bom` without `makeBom` and exits 0 having
uploaded nothing. `.../connector/ldap/Messages.properties` is never loaded by ConnId at all and has
drifted four properties behind.

## Skeptic pass

I re-read every blocker and major and tried to argue it away. What follows is what changed.

**Downgraded — `AbstractSchemaTranslator.java:1627`, O(N²) reference objects (blocker → major).**
The schema reviewer rated it blocker on a 20k-member group producing 4·10⁸ objects. But the
core-connector reviewer established independently that the whole reference/association path is inert
unless `managedAssociationPairs` is configured, and rated its own findings in that code major for
exactly that reason. Default configuration never reaches it. Real, and severe when enabled — but not
a blocker for a default deployment. Same logic applied to `ReferenceAttributeTranslator.java:148` and
`LdapFilterTranslator.java:441`, which stay major.

**Dropped — "`maximumNumberOfAttempts` is dead configuration".** The core-connector reviewer inferred
this from `ReconnectException` never being constructed. The error-handling pass checked the code and
found live `continue` paths that never touch `ReconnectException` — the `LdapConnectionTimeOutException`
reconnect in all three strategies, VLV's `BUSY` handling, and the referral fall-through. The
configuration is live. What survives is narrower: the four `instanceof ReconnectException` branches are
dead, and `searchSingleEntry`'s loop is effectively an `if`. This is the one place two reviewers
contradicted each other, and the adjudication cost one claim.

**Dropped — the `getMemberOfAllowedValues()` live-array write-back as a concurrency defect.** It looks
like a textbook shared-mutable-state bug and two reviewers flagged the array. The concurrency pass
established from `ConnectorPoolManager` that `AbstractLdapConfiguration` does not implement
`StatefulConfiguration`, so every pooled connector instance gets its own bean with
`SerializerUtil.cloneObject` copies of every array. The array is private per instance, and the
derivation is idempotent. Not a concurrency defect. It remains reported as a minor design smell only.
Worth recording that this conclusion is one interface declaration away from being false.

**Kept, but flagged as unverified — no TLS hostname verification (`ServerConnectionPool.java:455`).**
The claim is that Apache Directory API 2.1.7's `LdapNetworkConnection.addSslFilter()` never sets
`endpointIdentificationAlgorithm`, so even `allowUntrustedSsl=false` accepts any certificate from any
trusted CA for the eDirectory host. One reviewer read the API source and asserts it; nobody
corroborated it. If true it is the most serious security finding here, because it survives the obvious
fix to #6. **Confirm against api-all 2.1.7 before acting on it.**

**Kept, but flagged as unverified — `README.md:31`, the `connectorRef` OID.** I tried hardest to
dismiss this one and could not, but neither can I confirm it: the argument turns on whether midPoint
re-resolves a `connectorRef` filter when the stored OID no longer resolves. My own testing today does
not settle it — the test resource is `PUT` fresh on every run carrying a filter and no `oid`, so the
migration path for an *existing* resource with a resolved OID was never exercised. Verify on a copy of
a real resource before publishing the procedure.

**Kept after failing to dismiss — `allowUntrustedSsl` (#6).** The obvious defence is that the fork
changed it deliberately. I could not sustain that: the flip rides in commit `2869853`, whose message is
about removing `DiscoverConfigurationOp` and says nothing about TLS; the connector's own javadoc, both
message catalogues and the upstream page the README links all still say `false`; and both places in
this repository that actually need it — `docker/README.md` and `EDirTestSupport` — set it explicitly
anyway. Nothing here depends on the flipped default. It reads as an accident of local test convenience
that escaped into the shipped default.

**Kept after failing to dismiss — #2, `searchSingleEntry` (`:2165`).** The defence would be that
`cursor.next()` returning `false` on a non-SUCCESS result is unreachable because the error handler runs
first. The reviewer disassembled `api-ldap-client-api-2.1.0.jar` and confirmed `SearchCursorImpl.next()`
inspects only whether a done message arrived, never its result code. Nothing on that path consults it.

**Kept after failing to dismiss — #4, password in the create log.** The defence would be that INFO is
off in production. It is not a level midPoint disables — it is the level an operator raises the log to
in order to diagnose a provisioning failure, which is precisely when accounts are being created. The
adjacent modify path carries an explicit guard and a comment explaining the hazard, which tells me the
authors agreed it was real and simply missed one call site.

**Severity confirmed, not raised — #9, silent truncation (major).** Its consequence — reconciliation
deleting live accounts — is worse than several blockers. It stays major because it needs a server
returning an empty page with a live cookie, which is legal but not routine. Flagged here because
severity alone under-ranks it; it is in the top ten on blast radius.

## Coverage

Checked and found clean, recorded so it is not re-derived: filter escaping (everything routes through
Directory-API node construction — no injection path); DN suffix, RDN and AVA comparison; generalized-time
round-tripping and timezone handling; VLV and SPR page arithmetic; `recompute()` idempotency; iteration
over a collection modified in its own loop (none anywhere); `SystemSchemaLoader` stream handling;
`dispose()` and the whole `ConnectionManager.close()` chain; `GuardedString.toString()` and
`BindRequestImpl.toString()` (both safe); the ConnId threading model, established from the framework
jars rather than assumed; bundle identity consistency across pom, manifest, README and tests; and all 72
`@ConfigurationProperty` accessors having display and help keys. Git history was swept across 751
commits: no credential, keystore or private key has ever been tracked.

No static analysis is configured in this project and none was installed. `mvn dependency:analyze`
reports only `slf4j-simple` and `connector-framework-contract`, both benign and excluded from the
findings above.

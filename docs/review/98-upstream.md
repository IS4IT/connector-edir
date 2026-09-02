# 98 — Which findings belong upstream

Which of the review findings are defects in `Evolveum/connector-ldap` itself, and so are worth a PR
there instead of a local patch.

## Method

Not judged from memory. Every file under `src/main/java` was compared against
`refs/remotes/upstream/master`, and for each file that differs, the specific defect site was checked
in the upstream blob:

- **11 files are byte-identical** to upstream — `ConnectionLog`, `ErrorHandler`,
  `ConnectorBinaryAttributeDetector`, `ReconnectException`, `package-info`, and all of
  `schema/` except `AbstractSchemaTranslator`, plus `DefaultSearchStrategy`,
  `SimplePagedResultsSearchStrategy`, `VlvSearchStrategy`, `ModifyTimestampSyncStrategy`,
  `SyncStrategy`. A patch applies unmodified.
- **8 files differ**, but only because of this fork's logging, the `globalTimeout` rename and
  constant re-references. Each defect site was checked individually and quoted below where the
  answer was not obvious.
- **5 files are fork-only** — all of `edirectory/`.

Two greps did most of the work and both corrected an assumption I had made:

```
git grep -n "new ReconnectException" refs/remotes/upstream/master -- src/main/java   # → nothing
git show refs/remotes/upstream/master:.../AbstractLdapConfiguration.java | grep allowUntrustedSsl
                                                                          # → = false
```

## Upstream — worth a PR

Verified present in `upstream/master`. Ordered as in `99-summary.md`.

| finding | upstream evidence |
|---|---|
| **blocker** `AbstractSchemaTranslator.java:869` — `isBinarySyntax` / `!isBinarySyntax` partition the space, making the detected-binary fallback unreachable; `byte[]` is written as `[B@…` | site verbatim upstream. The eDirectory *symptom* is fork-only, but upstream reaches the same code through `AdSchemaTranslator`'s own binary syntaxes |
| **blocker** `AbstractLdapConnector.java:2165` — `searchSingleEntry` returns `null` for any non-SUCCESS `SearchResultDone` → `UnknownUidException` → shadow marked dead → account re-created | method body compared line by line: identical `cursor.next()` / `return null` / `instanceof ReconnectException` structure (upstream declares it `protected`, fork `private` — that is the only difference) |
| **blocker** `LdapUtil.java:444` — `closeDoneCursor` throws before `cursor.close()`, leaking the cursor and masking the real exception | site verbatim; upstream's `searchSingleEntry` `finally` calls `closeDoneCursor` then `returnConnection` in the same order, so the masking and the skipped release reproduce exactly |
| **blocker** `AbstractLdapConnector.java:1065` — the whole add `Entry` is logged at INFO after `processEntryBeforeCreate` decrypted the password | upstream: `processEntryBeforeCreate(entry)` at `:1007`, `OperationLog.logOperationReq(..., "Add REQ Entry:\n{0}", entry)` at `:1015`. Same eight-line gap |
| **blocker** `ServerConnectionPool.java:435` — bind failure calls `closeServerConnection` before `setConnection`, leaking socket + TLS session + MINA thread | site verbatim; fork differs only in re-enabled `LOG.ok` lines |
| **blocker** `DefaultSearchStrategy.java:145` — `referral` falls through with no `break`; the search is re-sent 10× and every entry re-delivered | file byte-identical |
| **major** `SimplePagedResultsSearchStrategy.java:218`, `VlvSearchStrategy.java:329` — silent truncation reported as `completeResultSet=true` | files byte-identical |
| **major** `SimplePagedResultsSearchStrategy.java:232`, `:321` — spurious unpaged second scan; `closeDoneCursor` in `finally` turns a completed import into a reported failure | file byte-identical |
| **major** `AbstractLdapConnector.java:1705`, `:1911` — post-decryption values in the exception message midPoint persists; `isSensitiveAttribute` masks one attribute name | both sites verbatim upstream |
| **major** `ConnectionLog.java:68`, `:151` — `error*` gates on `isError()` but emits via `LOG.info()`; `searchError` `MessageFormat` indexes shifted | file byte-identical |
| **major** `ErrorHandler.java:147`, `:154` — `OTHER` → `ConfigurationException`; `SIZE_LIMIT`/`TIME_LIMIT` → `PermissionDeniedException` | file byte-identical |
| **major** `LdapUtil.java:350`, `:278` — `isObjectClass` NPEs on a missing `objectClass`; inverted null branches in all three `logOperationError` overloads | sites verbatim |
| **major** `ModifyTimestampSyncStrategy.java:171`, `:216` — `EntryCursor` leaked on every error path; token from the client clock compared against server timestamps | file byte-identical |
| **major** `SyncStrategy.java:165`, `:170` — cursor leak; and the `{1}` reuse that must be **deleted, not renumbered** | file byte-identical |
| **major** `SearchStrategy.java:199`, `:208`, `:272` — guard/emit logger mismatch; `searchSuccess` logged for `REFERRAL`; no `try/finally` around `search()` | sites verbatim |
| **major** `ServerConnectionPool.java:449`/`:455`, `:530`, `:625`, `:442`, `:199` — TLS hostname verification never enabled; bind password into an unclearable `String`; runAs failure closes the shared pooled connection; failover on bad credentials risks account lockout | sites verbatim |
| **major** `ServerDefinition.java:142`, `:160` — server line keys not trimmed, unknown keys dropped silently; per-server `bindPassword` in a non-confidential `String[]` | sites verbatim |
| **major** `ConnectionManager.java:133`, `:401`, `:424` — connection dropped when the root DSE fetch fails; capability caches never invalidated; `supportedControls` left null → NPE | sites verbatim |
| **major** `AbstractSchemaTranslator.java:1627`, `:1944`, `:1989`, `:2209` — O(N²) reference objects; `addAll` inside the loop over the same list; `hashLdapPassword` leaves cleartext in an unclearable `String`; `determineAttributesToGet` null-guard after the dereference | sites verbatim |
| **major** `ReferenceAttributeTranslator.java:148`, `LdapFilterTranslator.java:250`, `:441` — unnamed attribute aborts the search; shadowed `translate(EqualsFilter)` builds a prefix filter; null `AttributeType` reaches `LeafNode` | files byte-identical |
| **major** `AbstractLdapConnector.java:1183`, `:1210`, `:1924` — `catch (Throwable)` swallowing and replaying whole operations | all three sites present upstream |
| **major** `ReconnectException` never constructed | **corrects my earlier assumption.** I expected upstream's AD error handler to raise it; `git grep "new ReconnectException"` over upstream `src/main/java` returns nothing. Reconnect-and-retry is dead upstream too |

## Fork-only — do not send upstream

| finding | why it is ours |
|---|---|
| **blocker** `allowUntrustedSsl = true` (`AbstractLdapConfiguration.java:99`) | upstream is `false`. Introduced here in `2869853`. The *hostname verification* half of the same finding (`ServerConnectionPool:455`) **is** upstream and is listed above |
| **blocker** `EDirTestSupport.java:261` purge deletes pre-existing `cn=test-*` | test rig added by this fork |
| **blocker** `README.md:31` `connectorRef` OID not repaired | migration procedure written here for the `connector-edir` rename |
| **major** `synchronizationStrategy.allowedValues` offers deleted strategies | upstream still implements all four; only this fork deleted three |
| **major** `ModifyTimestampSyncStrategy` can never emit `DELETE` | the code is identical upstream, but upstream has changelog/DirSync/accesslog strategies that can. The *code* is not an upstream defect; the *consequence* is ours alone |
| **major** `DiscoverConfigurationOp` dropped, methods left behind `//@Override` | removed here in `2869853` |
| **major** `port` `int`→`Integer` and the `validate()` unboxing NPE | fork-introduced |
| **major** `timeout` → `globalTimeout` and the README migration gaps | fork-introduced rename |
| all of `edirectory/*` — `isGroupObjectClass`, `__LOCK_OUT__`, `__ENABLE__` fail-open, filter/read disagreement, Novell syntax `isBinarySyntax` gap, reciprocal membership, `discoverConfiguration()` | package does not exist upstream (see the exception below) |
| all test findings, `docker/*`, `README.md`, `.github/copilot-instructions.md`, `Messages.properties` display key, `dependencytrack` profile | added or authored here |

## Two that need splitting

**`isGroupObjectClass` — fork symptom, upstream defect.** Our finding is in
`EDirectorySchemaTranslator:133`, which does not exist upstream. But upstream's
`AdSchemaTranslator` has the identical asymmetry, and I checked it:

```java
342: public boolean isUserObjectClass(ObjectClass ldapObjectClass) {   // walks superiors
352: public boolean isGroupObjectClass(String ldapObjectClass) {       // exact equals
353:     return getConfiguration().getGroupObjectClass().equals(ldapObjectClass);
```

Upstream `2991d36` fixed only the user side; I reproduced that asymmetry faithfully when porting it
in `0d9d63f`. **A PR giving `isGroupObjectClass` the same `ObjectClass`-typed superior walk fixes AD
upstream and eDirectory here**, and is the cleanest single upstream contribution in this list —
small, self-contained, and it closes a gap upstream already agreed with in principle.

**Binary write corruption — one root cause, two symptoms.** The unreachable fallback at
`AbstractSchemaTranslator:869` is upstream and should be fixed there. The eDirectory half — Octet
List and Replica Pointer declared `byte[]` by `toConnIdType` but missing from `isBinarySyntax` — is
ours. Fixing only one leaves the other broken, so land the upstream patch first, then the local
override.

## Suggested PR grouping

Small, single-theme PRs land better than one large one, and these are largely independent:

1. **`isGroupObjectClass` superior walk** — the easy win described above.
2. **Cursor lifecycle** — `LdapUtil.closeDoneCursor` closing before it complains, plus `try/finally`
   around the cursor and the connection in all three search strategies and
   `ModifyTimestampSyncStrategy`. One coherent story about leaks and masked exceptions.
3. **Failures reported as success** — `searchSingleEntry`'s `null`, the two silent truncations, and
   `searchSuccess` logged for non-SUCCESS results. This is the highest-value group for midPoint
   users, because the failure mode is reconciliation deleting or duplicating accounts.
4. **Credentials in logs and exception messages** — the create-path entry dump, the modify exception
   message, and `isSensitiveAttribute`'s single-name mask. Security-flavoured, likely to get
   attention.
5. **Logging plumbing** — `ConnectionLog` `error*` emitting at INFO, the `searchError` format
   indexes, `logOperationError`'s inverted branches. Note in the PR that `SyncStrategy:170`'s `{1}`
   must be **deleted rather than renumbered**, or the fix introduces a full entry dump.
6. **`referral` fall-through** in `DefaultSearchStrategy`.
7. **TLS hostname verification** — hold until the api-all claim in `99-summary.md` is confirmed.

Worth saying in any PR description that these came from a review of a fork and were verified against
`upstream/master`, so a maintainer does not have to work out whether the line numbers apply.

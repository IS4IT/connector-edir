# Secrets & logging — cross-cutting pass

## Verdict

The core design is sound and inherited from upstream: the bind password and account
passwords stay inside `GuardedString` for their whole journey through the connector,
`GuardedStringValue` deliberately defers decryption to the last moment before the wire,
and the two decryption points (`processEntryBeforeCreate`, `processModificationsBeforeUpdate`)
sit after the request-logging calls on the happy path. `ServerDefinition.toString()`,
`ServerDefinition.dump()` and `LdapUtil.formatConnectionInfo()` all omit the password,
no configuration class has a `toString()` that could leak one, nothing writes to
`System.out`/`System.err` outside a unit test, and no real credential has ever been
committed to this repository. What is weak is everything downstream of decryption: once
a `GuardedString` has been turned into plaintext the connector treats the result as an
ordinary attribute value, and the only thing standing between that value and the log —
or an exception message midPoint persists — is a single exact-name comparison against
`passwordAttribute`. That single-name mask is the real hole, and it is compounded by a
recurring habit of gating a log call on one logger's level while emitting through
another's. Nothing here is a blocker on its own, but the modify error path is one
misconfigured attribute away from writing a live credential to disk at ERROR level.

## Findings

major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:1911 | `isSensitiveAttribute()` masks exactly one attribute name (`configuration.getPasswordAttribute()`, default `userPassword`), so any *other* attribute midPoint sends as a `GuardedString` — an eDirectory `nspmDistributionPassword` write while `passwordAttribute=userPassword`, or the reverse when the universal password is the managed one — is decrypted in place by `processModificationsBeforeUpdate` (:1833) and then printed in full cleartext by `dumpModifications()` on the failure paths at :1695 (`LOG.error`, on by default, no level guard) and :1705, and by `EDirectoryLdapConnector:195`/`:196` | replace the single-name comparison with a set-valued check (a `sensitiveAttributes` config property, seeded with the eDirectory credential attributes `userPassword`, `nspmDistributionPassword`, `nspmPassword`), and mask on value *type* too — treat any value that arrived as a `GuardedStringValue` as sensitive regardless of attribute name

major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:1705 | the ConnId exception message is built as `"Error modifying LDAP entry "+dn+": "+dumpModifications(modifications)`, i.e. every non-masked attribute value of the failed modify — post-decryption — is embedded in an exception midPoint stores in the shadow's operation result and renders in the GUI and audit trail, so the exposure survives log rotation and any log-level change; `EDirectoryLdapConnector:196` does the same for the password-constraint case | put only the DN, the attribute *names* and the LDAP result code in the exception message; keep `dumpModifications()` for the log only

minor | src/main/java/com/evolveum/polygon/connector/ldap/connection/ServerConnectionPool.java:530 | `bindRequest.setCredentials(new String(chars))` copies the eDirectory service-account bind password — and, on the `runAs` path at :623, an end user's password — out of the `GuardedString` into an immutable `String` that cannot be zeroed, and `BindRequestImpl` then keeps it as a `byte[]` it never wipes; the `char[]` the accessor was handed is cleared on return but these two copies are not, so any midPoint heap dump taken for support contains the directory admin password in cleartext | call `setCredentials(byte[])` with a UTF-8 array built from `chars`, and `Arrays.fill(..., (byte) 0)` it once `connection.bind(bindRequest)` returns

minor | src/main/java/com/evolveum/polygon/connector/ldap/search/SearchStrategy.java:199 | the full-entry dump is gated on `LOG.isOk()` — DEBUG on `SearchStrategy`'s own logger — but emitted through `OperationLog.logOperationRes`, which stamps the record INFO under a *different* logger; enabling DEBUG for the search package therefore writes complete directory entries (including the password attribute whenever `passwordReadStrategy=readable`) as INFO-level records, which a level-based appender or redaction filter configured for INFO will forward. Same mismatch at `ModifyTimestampSyncStrategy.java:145` and `AbstractLdapConnector.java:1669` | emit at the level the guard tests — add debug-level variants to `OperationLog`, or gate these call sites on `OperationLog.isLogOperations()` so guard and emit agree

## Checked and clean

- **`GuardedString.toString()`** — ConnId does not override it, so `GuardedStringValue`'s
  `super(attributeType, val.toString())` (`GuardedStringValue.java:36`) stores the harmless
  `org.identityconnectors.common.security.GuardedString@<hash>` as the value's string form.
  The pre-decryption dumps at `AbstractLdapConnector:1054` and `:1670` therefore print that
  placeholder, not the password. It looks alarming; it is not.
- **`BindRequestImpl.toString()`** in Apache Directory API 2.1.7 prints
  `(omitted-for-safety)` in place of both simple and SASL credentials, so the bind request
  cannot leak through the library's own DEBUG logging of the outgoing message.
- **`@ConfigurationProperty` on `getBindPassword()`** (`AbstractLdapConfiguration.java:673`)
  has no `confidential = true`, but that flag is irrelevant here: midPoint maps a
  `GuardedString`-typed property to `ProtectedStringType` by Java type, so the value is
  encrypted at rest regardless. (The `servers` line is a different story — already reported.)
- **`GuardedString.access` call sites** — all five (`ServerConnectionPool:527`,
  `AbstractLdapConnector:1840` and `:1861`, `AbstractSchemaTranslator:1986`) keep the
  plaintext inside the accessor body. `ServerConnectionPool`'s anonymous subclass of
  `GuardedStringAccessor` overrides `access()` without calling `super`, so the base class's
  retained `clearChars` field stays null — correct, though it depends on nobody ever
  "fixing" the missing `super` call.
- **`toString()` implementations** — only four exist (`ServerDefinition`, `ScopedFilter`,
  `AssociationHolder`, `LdapObjectClasses`); none touches a credential. No configuration
  class defines one.
- **`System.out` / `System.err`** — two occurrences, both in `TestLdapUtil.java:70,74`,
  printing DNs in a unit test.
- **Git history** — 751 commits swept for literal credential assignments. The only hits are
  upstream Evolveum's placeholder `bindPassword=secret` in a since-deleted
  `src/test/resources/integration/test.properties`, and this fork's throwaway rig values in
  the two `*.example` files (already reported). `docker/.env` and
  `src/test/resources/test.properties` are both in `.gitignore` and were never committed.
  No keystores, PEM files or private keys are or ever were tracked.
- **`docker/docker-compose.yml`** takes every credential from `${...}` env vars with no
  hardcoded fallback; `docker/rig` never touches a password. `.vscode/settings.json`,
  `.issuetracker`, both `Messages.properties` and the READMEs contain no secrets.
- **`searchSingleEntry` entry dump** (`AbstractLdapConnector.java:2144`) *is* gated on
  `isInfo()` and so runs by default, but every reachable caller passes a narrow
  `attributesToGet` (`{uidAttribute}` at :1119/:2062, `{associationAttributeName}` at
  :1560). The one caller that passes `null` — and would therefore get `*` back — is the
  poly-attribute pre-read at :1799, unreachable on eDirectory because
  `toLdapPolyValues()` throws `UnsupportedOperationException` and
  `EDirectorySchemaTranslator` does not override it.
- **`ErrorHandler`** puts only the connector-supplied message and the server's diagnostic
  message into ConnId exceptions; `ServerConnectionPool.bind()`'s failure messages carry
  host, port and bind DN but never the credential.
- **No `AuthenticateOp`** — the connector exposes no password-verification operation, so
  there is no third path by which an end-user password enters it beyond `runWithPassword`.

## Caution for whoever fixes the sibling format-string bugs

`SyncStrategy.java:170` intends to log a whole `Entry` at ERROR but reuses `{1}`, so
`MessageFormat` renders `baseDn` where the entry should go and nothing leaks today.
Correcting the index — the obvious fix, and the same shape as the already-reported
`ConnectionLog.searchError:151` off-by-one — turns it into a default-on, unguarded full
entry dump. Drop the argument rather than renumbering it.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A ConnId/Polygon connector for Novell/OpenText eDirectory, built as a ConnId bundle jar and
deployed into midPoint or a ConnID Java Connector Server. A common use case is migrating from
NetIQ/OpenText Identity Manager to midPoint, so trees usually carry DirXML schema and custom
object classes derived from the standard ones.

It is a **stripped fork of `Evolveum/connector-ldap`**. The generic LDAP connector, the AD
connector and three of the four sync strategies were deleted. What remains is the shared
`AbstractLdap*` base plus the `edirectory` package.

## The fork relationship — read this before editing anything under `src/main/java`

The shared base classes are kept in-tree, in their original
`com.evolveum.polygon.connector.ldap` package, **specifically so upstream commits stay
cherry-pickable** (`README.md:6`). Do not repackage, rename or "tidy" them; that is the one
thing the layout exists to protect.

Before changing a file, know which kind it is. Roughly 18 files under `src/main/java` are
byte-identical to upstream, 10 differ only by this fork's logging and renames, and 5 (all of
`edirectory/`) exist only here:

```bash
git diff --quiet refs/remotes/upstream/master HEAD -- <file> && echo IDENTICAL || echo DIFFERS
git cat-file -e refs/remotes/upstream/master:<file> || echo FORK-ONLY
```

A bug in an identical or near-identical file is usually an **upstream** bug and belongs in a PR
to Evolveum rather than a local patch. `docs/review/98-upstream.md` classifies the known ones
and groups them into suggested PRs. Note the remote is called `upstream`, and there is also a
local branch literally named `upstream/master`, so refs are ambiguous — always use
`refs/remotes/upstream/master`.

Fork-local divergences worth knowing: `timeout` was renamed to `globalTimeout`, `port` is
`Integer` rather than `int`, and `DiscoverConfigurationOp` was dropped from the implements
clause while its methods remain behind commented-out `//@Override` (so wizard discovery and the
partial connection test silently do nothing).

## Build and test

```bash
mvn clean package                 # produces target/connector-edir-<version>.jar
mvn test                          # live suites skip themselves when unconfigured
mvn test -Dtest=TestEDirectory    # one class
mvn test -Dtest=TestEDirectory#test145EnableFilterAgreesWithRead   # one method
```

Java 17. TestNG, not JUnit — surefire needs the explicit `surefire-testng` provider declared in
`pom.xml`, or every test is skipped silently. **No static analysis is configured** (no spotbugs,
pmd, checkstyle, errorprone) and none is inherited from `connector-parent`.

When shelling out, note that `mvn ... | tail` masks the exit status; use `set -o pipefail` before
concluding a build passed.

### Test configuration

Live tests read `src/test/resources/test.properties` **off the classpath**, not from `-D`
flags — that is what makes them behave identically in an IDE and on the command line. Copy
`test.properties.example`; its defaults describe the docker rig and work unedited. A system
property still overrides the file for a one-off (`-Dtest.edir.port=1636`).

Both suites skip when the file is absent **or** the server is unreachable, and fail when a
reachable server rejects the credentials. Deleting `test.properties` does not take effect until
`mvn clean` — `target/test-classes/` keeps the old copy.

The suites can be pointed at an existing eDirectory or midPoint, not just the rig, which is why
`EDirTestSupport.purgePreviousRuns` is scoped tightly: only the configured containers and object
classes, only names matching the generated `test-<purpose>-<epoch millis>` shape, with each DN
re-checked against its container before deletion. `TestEDirTestSupport` covers that matcher and
needs no server. Be careful loosening it.

Test objects are **left behind on purpose** for inspection; the next run purges the previous
run's.

## Test rig

```bash
docker/rig init      # up, wait for the tree, seed o=data  (~10 min on first build)
docker/rig deploy    # mvn clean package, stage the jar into midPoint, restart it
docker/rig seed | status | stop | down | wipe
```

Anything else is passed through to `docker compose`. See `docker/README.md` for the full
picture; the parts that bite:

- **`docker compose up` alone is not enough.** The vendor image restarts the Identity Vault part
  way through configuration and intermittently wedges on a "Could not find prompt ID" race that
  only a log truncation and restart clears. `docker/rig` handles both.
- **eDirectory is configured entirely through compose `environment:`**, not a mounted
  `silent.properties` — `/startidm.sh` replaces that file with a dump of the container
  environment whenever `INSTALL_ENGINE` is set, and only environment entries interpolate from
  `.env`, which is what makes the ports configurable.
- **No bind mounts anywhere.** The Docker context is colima, whose VM does not see every host
  path, and a bind mount of an unshared path silently appears as an empty directory instead of
  failing. Seed data is inlined in the compose file; jars are staged with `docker compose cp`.
- **The tree survives `down`** — it lives in the `edir_config` volume, because the container
  scripts keep eDirectory's data under `/config/idm/eDirectory_data` and expose it at the legacy
  path (same inode). Only `wipe` discards it. Do **not** add a volume for
  `/var/opt/novell/eDirectory`; it breaks `ndsconfig`.
- **Do not interrupt the first `init`.** eDirectory decides it is configured by the presence of
  `/config/idm/version.properties`, written only at the end; a container stopped before that
  cannot be restarted and must be wiped.
- Plaintext LDAP is refused by design (the image ends setup with "Require TLS for Simple Binds"),
  so tests use `connectionSecurity=ssl` with `allowUntrustedSsl=true` set **explicitly** — that
  is not the connector's default.

The eDirectory image is licensed and lives in a private registry (`docker login hub.is4it.de`),
linux/amd64 only, so the rig cannot run in public CI.

## Architecture

Template method throughout: `AbstractLdapConnector` / `AbstractSchemaTranslator` hold the logic,
`EDirectoryLdapConnector` / `EDirectorySchemaTranslator` specialise it. A subclass must override
`createSchemaTranslator()` and `createErrorHandler()` — returning `null` from the latter was a
real bug that NPE'd every search by DN.

- **`schema/AbstractSchemaTranslator`** (~2.6k lines) is where most behaviour lives: LDAP schema
  to ConnId schema, entries to `ConnectorObject`, ConnId values back to LDAP `Value`. Type
  decisions here are made by syntax OID, and read and write paths are **not** symmetric — a
  syntax declared `byte[]` by `toConnIdType` must also be listed in `isBinarySyntax`, or writes
  are stringified into `[B@...`.
- **`connection/`** — `ConnectionManager` per connector instance, `ServerConnectionPool` doing
  one-connection-per-server with failover, `ServerDefinition` parsing the multi-server config.
- **`search/`** — `DefaultSearchStrategy`, `SimplePagedResultsSearchStrategy` (RFC 2696, the
  usual one) and `VlvSearchStrategy`, chosen per search request, not at init.
- **`sync/`** — only `ModifyTimestampSyncStrategy` survives the fork. It hardcodes
  `CREATE_OR_UPDATE`, so **the connector can never report a deletion** via live sync;
  reconciliation is required for deprovisioning. Its token comes from the connector host's clock,
  not the server's.

ConnId gives each pooled connector instance its own configuration bean (because
`AbstractLdapConfiguration` does not implement `StatefulConfiguration`) and never hands one
instance to two threads, so instance state is effectively single-threaded. The one genuine
exception is `ConnectorBinaryAttributeDetector`, which the Apache Directory API calls from the
MINA I/O thread.

### eDirectory specifics

- `__ENABLE__` maps to `loginDisabled` **inverted**. `loginDisabled=FALSE` and an absent
  `loginDisabled` are the same state, so filters use `(!(loginDisabled=TRUE))` for enabled.
- `__LOCK_OUT__` is derived from `lockedByIntruder` **and** `loginIntruderResetTime`;
  `determineAttributesToGet` is overridden to request both, since ConnId only knows the one
  attribute `toLdapAttribute` maps it to.
- `isUserObjectClass` / `isGroupObjectClass` walk superior object classes and compare
  case-insensitively — derived classes are normal in migrated trees.
- Group membership is reciprocal: `member` on the group plus `groupMembership` on each member,
  and `equivalentToMe` when `manageEquivalenceAttributes` is set. The member writes happen in
  `postUpdate`, after the group's own `member` has been committed, and have no compensation on
  partial failure — a known open issue.
- `uidAttribute` defaults to `GUID`, a binary attribute, so `resolveDn` → `searchSingleEntry`
  runs on every update, rename and delete.

## Bundle identity

`connector-parent` derives `ConnectorBundle-Name` from `${project.groupId}.${project.artifactId}`,
currently `com.evolveum.polygon.connector-edir`. midPoint builds each resource's configuration
schema namespace from that plus the connector class:

```
.../icf-1/bundle/{ConnectorBundle-Name}/{connectorType}
```

So **renaming the artifact invalidates the namespace in every deployed resource**. The artifact
was renamed from `connector-ldap` precisely because it collided with the connector-ldap Evolveum
ships inside the midPoint image, and ConnId refuses two bundles sharing name and version.
`TestMidPointIntegration.test000ConnectorIsDiscovered` asserts the deployed bundle still matches
what `resource-edir.xml` declares, and is the thing that will catch such a change.

Deploy the bundle jar only — never `connector-edir-<version>-sources.jar`, which
`maven-source-plugin` leaves alongside it and ConnId fails to parse at startup.

## Existing review

`docs/review/` holds a full code review: per-unit reports, cross-cutting passes, `99-summary.md`
with a skeptic pass, and `98-upstream.md` classifying which findings belong upstream and grouping
them into suggested PRs. Check it before re-deriving a finding.

It is a **snapshot, not a live issue list**. Many findings have since been fixed — see the commits
following `fd4d1c4` — and one was wrong and reverted. Two are explicitly marked unverified in
`99-summary.md`: the claim that Apache Directory API 2.1.7 never enables TLS hostname
verification, and the `connectorRef` OID step in the README migration. Treat anything in there as
a lead to re-check, not a fact.

The most substantial known-open item is reciprocal group membership having no compensation on
partial failure (`EDirectoryLdapConnector`, `postUpdate` → `addGroupMemberShipModifications`).

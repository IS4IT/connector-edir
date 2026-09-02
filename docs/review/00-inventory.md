# 00 — Inventory

Repository: `connector-edir` (branch `edirectory`), a stripped fork of
`Evolveum/connector-ldap`. Scope: whole repository. `target/` excluded; no generated
sources exist.

## Phase 0 notes

**Grounding documents.** No `CLAUDE.md`, no `DESIGN.md`. `README.md` (45 lines) and
`.github/copilot-instructions.md` (122 lines) are the only architecture statements;
`docker/README.md` documents the test rig.

Deliberate conventions extracted — these are **not** findings:

- Template-method design: `AbstractLdapConnector` / `AbstractSchemaTranslator` with a
  per-directory subclass. Subclasses must override `createSchemaTranslator()` and
  `createErrorHandler()`.
- Logging goes through `org.identityconnectors.common.logging.Log`, never slf4j/log4j
  directly. `OperationLog` carries operation-level logging.
- The shared `AbstractLdap*` base is kept in-tree, in the original
  `com.evolveum.polygon.connector.ldap` package, specifically so upstream commits stay
  cherry-pickable (`README.md:6`). Package-level "cleanups" would defeat this.
- Configuration constants live in `AbstractLdapConfiguration` as `CONF_PROP_NAME_*`.

**`.github/copilot-instructions.md` is stale** — it documents `LdapConnector`,
`AdLdapConnector`, `SunChangelogSyncStrategy`, `AdDirSyncStrategy`,
`OpenLdapAccessLogSyncStrategy` and a `-Dtest.ad.*` test workflow, none of which exist on
this branch. Carried into Phase 3.5 (design drift) rather than reported here.

**Static analysis: none configured.** No spotbugs, pmd, checkstyle, errorprone, sonar or
jacoco in `pom.xml`, and none inherited from `connector-parent:1.5.3.0-M3`. Nothing was
installed.

**`mvn dependency:analyze`** reports only two unused declared dependencies, both benign and
treated as known: `slf4j-simple` (a runtime logging binding, so no compile-time reference is
expected) and `connector-framework-contract` (inherited from `connector-parent`). No used-but-
undeclared dependencies.

## Inventory

LOC is raw line count including comments and licence headers. Churn is commits touching the
path in the last 12 months.

| module/package | purpose | files | LOC | commits (12mo) | risk |
|---|---|---|---|---|---|
| `ldap/` (root, non-recursive) | connector base, config, shared utilities, error mapping | 10 | 4,897 | 23 | **H** — contains the two largest classes; every operation passes through them |
| `ldap/schema/` | LDAP ↔ ConnId schema and value translation, filters, associations | 9 | 3,794 | 4 | **H** — 2,635 LOC in one class; type/binary-syntax decisions affect every attribute, and low churn hides that |
| `ldap/connection/` | connection pooling, failover, TLS, bind | 3 | 1,777 | 5 | **H** — TLS setup and credential handling; failover paths are the hardest to exercise |
| `ldap/search/` | paged / VLV / simple search strategies | 4 | 1,252 | 1 | **M** — paging correctness, cursor lifecycle; nearly untouched |
| `ldap/edirectory/` | the eDirectory connector this fork exists for | 5 | 821 | 14 | **H** — highest-churn Java in the repo, all fork-specific, already yielded one null-dereference defect |
| `ldap/sync/` | modifyTimestamp sync strategy | 2 | 420 | 1 | **M** — token format and boundary handling |
| `src/test/java` | live integration suites + shared support | 4 | 1,169 | 6 | **M** — these mutate a live directory, including deletes |
| `src/main/resources` | ConnId message catalogues, logging.properties | 3 | 556 | 9 | **L** — text only, but keys must match configuration property names |
| `src/test/resources` | midPoint resource template, test.properties.example | 3 | 218 | 4 | **M** — the example carries working credentials |
| `docker/` | eDirectory + midPoint test rig, `rig` control script | 5 | 733 | 3 | **M** — credentials, a licensed image, and a script that deletes containers |
| `pom.xml` | build, bundle identity, dependencies | 1 | 253 | 16 | **M** — highest-churn file; carries `ConnectorBundle-Name` |

Hottest individual files (12 months): `pom.xml` (16),
`edirectory/EDirectoryLdapConnector.java` (10), the two `Messages.properties` (7 and 6),
`AbstractLdapConnector.java` (6), `edirectory/EDirectorySchemaTranslator.java` (5).

## Proposed review units

Nine units, split to keep each subagent's reading load bounded and each unit internally
coherent. The root package is split three ways because it is 4,897 LOC of unrelated
concerns.

| # | unit | contents | LOC | risk |
|---|---|---|---|---|
| 1 | `core-connector` | `AbstractLdapConnector.java` | 2,244 | H |
| 2 | `core-config` | `AbstractLdapConfiguration.java`, `LdapConstants.java` | 1,438 | H |
| 3 | `schema` | all of `ldap/schema/` | 3,794 | H |
| 4 | `connection` | all of `ldap/connection/` | 1,777 | H |
| 5 | `edirectory` | all of `ldap/edirectory/` | 821 | H |
| 6 | `search-sync` | all of `ldap/search/` and `ldap/sync/` | 1,672 | M |
| 7 | `util-error-logging` | `LdapUtil`, `ErrorHandler`, `ConnectionLog`, `OperationLog`, `ConnectorBinaryAttributeDetector`, `ReconnectException`, `package-info` | 1,215 | M |
| 8 | `tests` | `src/test/java`, `src/test/resources` | 1,387 | M |
| 9 | `build-and-rig` | `pom.xml`, `docker/`, `src/main/resources` | 1,542 | M |

Unit 3 is the largest and is dominated by a single 2,635-line class; if it comes back thin
I would rather re-run it split by concern (type mapping / value conversion / associations)
than accept a shallow pass.

Phases 3 and 4 add five cross-cutting subagents and a synthesis pass, as specified.

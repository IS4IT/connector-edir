# 14 — Design drift: documentation vs. code

## Verdict

The drift splits cleanly along authorship. Everything written for this fork — `docker/README.md`,
`docker/.env.example`, `test.properties.example`, the compose comments and the newer code comments —
is close to the code and drifts only in details (a wrong timeout, an undocumented subcommand, a purge
described as wider than it is). Everything inherited from Evolveum still describes the pre-fork
connector: `.github/copilot-instructions.md` documents three deleted classes, an API that does not
exist and a sync token that is never fetched from the server; both `Messages.properties` files and the
configuration javadoc still advertise sync strategies, changelog knobs and a discovery operation that
were removed. The dangerous part is `README.md`'s migration procedure. It is correct about the one
thing it set out to explain — the configuration-schema namespace — and silent about the two things
that will actually break or degrade a migrated resource: the `connectorRef` OID that step 2 invalidates,
and the `allowUntrustedSsl` default this fork flipped from `false` to `true`. An administrator who
follows those six paragraphs literally ends up with resources that either do not resolve a connector at
all, or that quietly stopped validating the LDAPS certificate.

Confirming the four already-known items, briefly: `.github/copilot-instructions.md` does document
`LdapConnector`, `AdLdapConnector` and three deleted sync strategies plus a `-Dtest.ad.*` workflow —
none exist (`src/main/java/.../ldap/` has only `AbstractLdapConnector` and the `edirectory` package;
`sync/` holds only `SyncStrategy` and `ModifyTimestampSyncStrategy`). `README.md`'s migration omits the
fork-local `timeout` → `globalTimeout` rename (commit c7fc196, `AbstractLdapConfiguration.java:132`).
`docker/docker-compose.yml:29` still tells you to copy jars into a `docker/connectors/` directory that
does not exist and that the compose file itself replaced with a named volume. And both
`Messages.properties` files claim `allowUntrustedSsl` defaults to `false` while
`AbstractLdapConfiguration.java:99` sets it to `true`. All four confirmed; findings below are the rest.

## Findings

`blocker | README.md:31 | step 2 tells the administrator to delete the old ConnectorType, but nothing in the procedure repairs `connectorRef`: midPoint stores a resolved OID on that reference, the restart registers `connector-edir` under a *new* ConnectorType OID, and every migrated resource then fails to load its connector — and the closing "export, rewrite, re-import" does not fix it, because an exported resource carries the stale `oid` attribute along with the filter | doc: add a step to strip the `oid=` attribute from every `connectorRef` (leaving the filter of step 4 to re-resolve it), and say that step 2 is only needed when the old and new jars carry the same connector version, since that is what makes the old ConnectorType shadow the new bundle at all`

`blocker | README.md:37 | the namespace rewrite preserves configuration properties verbatim, so a resource migrated from Evolveum's connector-ldap that never set `allowUntrustedSsl` silently switches from validating the LDAPS server certificate to not validating it — this fork flipped the default to `true` in commit 2869853 (`AbstractLdapConfiguration.java:99`), against its own javadoc at :95-97, both `Messages.properties:38`, and the upstream page README.md:4 sends the reader to | code: restore `allowUntrustedSsl = false`; three documents and the migration procedure all assume it, and `docker/README.md:98` plus `EDirTestSupport.java:284` already set it explicitly for the rig, so nothing in this repo depends on the flipped default`

`major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:948 | the `synchronizationStrategy` property still advertises `sunChangeLog`, `openLdapAccessLog` and `adDirSync` in `allowedValues`, which is what midPoint renders as the dropdown; picking any of them makes every `sync()` and `getLatestSyncToken()` throw `IllegalArgumentException: Unknown synchronization strategy` at `AbstractLdapConnector.java:1945`, i.e. a live-sync task that fails on first run | code: trim `allowedValues` to `none`/`auto`/`modifyTimestamp` and delete the three orphan constants at :375-378; `edirectory/Messages.properties:128` already documents only "none" or "modifyTimestamp"`

`major | .github/copilot-instructions.md:62 | "ModifyTimestampSyncStrategy.getLatestSyncToken() queries modifyTimestamp values" is false — `ModifyTimestampSyncStrategy.java:215-217` returns `System.currentTimeMillis()` formatted as a generalized time and makes no LDAP request at all, so the sync window depends on the midPoint host's clock matching eDirectory's (the code says so itself at :182); a reader who believes the doc will not think to check clock skew when changes are silently skipped or replayed | doc: state that the token is the connector host's clock and that skew loses or repeats changes`

`major | .github/copilot-instructions.md:64 | "Returns SyncDelta objects with ADD/MODIFY/DELETE change types" — `ModifyTimestampSyncStrategy.java:155` hardcodes `SyncDeltaType.CREATE_OR_UPDATE` and there is no delete detection anywhere in `sync/`, so a midPoint live-sync configured on the strength of this sentence never learns about deleted eDirectory accounts and leaves their shadows forever | doc: say that the only strategy left cannot detect deletes and that reconciliation is required for them`

`major | README.md:4 | the reader is sent to Evolveum's `EDirectoryLdapConnector` page "for the details", but that page documents the pre-fork connector: bundle `com.evolveum.polygon.connector-ldap`, a `timeout` property, `allowUntrustedSsl` defaulting to false, configuration discovery, and sync strategies this build rejects — every one of which this fork changed, and three of which are exactly what the migration section is about | doc: keep the link but list what differs here, or the page becomes the authority on a connector that no longer behaves that way`

`major | src/test/resources/test.properties.example:45 | "Set the user class to your own if the tree defines one derived from inetOrgPerson … this is how that gets tested" is contradicted by `TestMidPointIntegration.java:255`, which hardcodes `ri:inetOrgPerson` in the shadow query while the fixture account is created with the configured class — following the documented setup makes `test110SearchShadows` fail with a shadow-not-found assertion that says nothing about the cause | code: build the query from `EDirTestSupport.userObjectClass()` like the rest of the suite does`

`major | src/main/java/com/evolveum/polygon/connector/ldap/edirectory/EDirectorySchemaTranslator.java:133 | `isGroupObjectClass` is an exact name match, while the comment eleven lines above (:118-121) explains that eDirectory trees routinely derive their own classes and `isUserObjectClass` (:123) therefore walks superiors; the invariant holds on the user side only, so configuring a derived group class — which `test.properties.example:48` invites — silently disables the reciprocal `groupMembership` and `equivalentToMe` writes at `EDirectoryLdapConnector.java:121` and :244, leaving members in `member` but not in `groupMembership` | code: resolve superiors on the group side too, and take an `ObjectClass` rather than a `String` so the two checks cannot drift again`

`major | src/main/java/com/evolveum/polygon/connector/ldap/edirectory/EDirectoryLdapConnector.java:313 | the javadoc "Discovers eDirectory LDAP connector configuration suggestions" describes an operation ConnId never invokes: `DiscoverConfigurationOp` was dropped from the `implements` clause in commit 2869853 and `AbstractLdapConnector.java:147,154` now carry `//@Override`, so midPoint's resource wizard gets no suggested base contexts and its partial connection test does nothing — a visible regression for anyone migrating from Evolveum's connector, and unmentioned in README.md's migration | code: either re-declare `DiscoverConfigurationOp` and restore the annotations, or delete both methods plus the `addServerSpecificConfigurationSuggestions` hook (:367) that `.github/copilot-instructions.md:14` still tells new subclasses to override`

`minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:541 | "Parameter is used solely in configuration discovery to compute configuration suggestions for managedAssociationPairs" (repeated in both `Messages.properties:220`) now means "used nowhere": `groupObjectClasses` has no reader in `src/main`, yet midPoint still shows the field, so an administrator fills it in and nothing happens | code: delete the property, or doc: label it OBSOLETE/IGNORED the way `referralStrategy` (:230) and `checkAliveRootDse` (:183) already are`

`minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:406 | the javadoc for `changeLogBlockSize` and `changeNumberAttribute` (:404-411) still describes them as live changelog settings; nothing in `src/main` reads either, and `edirectory/Messages.properties:142,145` already appends "(IGNORED)" to their display names — the javadoc and the GUI now say opposite things about the same two fields | doc/code: mark them obsolete in the javadoc too, or drop them with the changelog strategy they belonged to`

`minor | src/main/resources/com/evolveum/polygon/connector/ldap/edirectory/Messages.properties:17 | the catalog defines `connector.ldap.display=LDAP Connector`, which nothing reads, and does not define `connector.ldap.edirectory.display`, which is the `displayNameKey` declared at `EDirectoryLdapConnector.java:42` — ConnId falls back to the key itself, so midPoint lists this connector as the literal string "connector.ldap.edirectory.display" | code: rename the key to `connector.ldap.edirectory.display` and give it an eDirectory-specific value; the whole point of the artifact rename was a distinct identity`

`minor | src/main/resources/com/evolveum/polygon/connector/ldap/edirectory/Messages.properties:155 | `timestampPresentation.help` says 'Possible values: "unixEpoch", "string", default value: "unixEpoch"', but the code's default is `native` (`AbstractLdapConfiguration.java:457`) and `native` is an allowed value (:1047) that the help text does not mention — someone writing mappings on the strength of this expects epoch seconds and gets framework-native timestamps | doc: list all three values and name `native` as the default (same line in `ldap/Messages.properties`)`

`minor | docker/README.md:12 | "The healthcheck allows 30 minutes before calling the container unhealthy" understates it: `docker-compose.yml:130` sets `start_period: 40m` plus 5×15s retries, and `docker/rig:70` waits 60 minutes — someone who kills a slow first boot at the 30-minute mark destroys the tree, since a container stopped before `/config/idm/version.properties` appears cannot be restarted (the same document says so at :128) | doc: quote the real numbers; the code is the safer of the two`

`minor | docker/README.md:37 | the command table lists init/deploy/status/stop/down/wipe/logs but omits `docker/rig seed` (`docker/rig:78,141`), the only documented way to re-create `o=data` after a wipe of the seeded containers without a full `init` | doc: add it`

`minor | docker/README.md:74 | "That packages the connector, copies it into /opt/midpoint/var/icf-connectors/ and restarts midPoint" omits both destructive halves of `cmd_deploy` (`docker/rig:92,105`): it runs `mvn clean`, discarding everything in `target/`, and `rm -f /opt/midpoint/var/icf-connectors/connector-*.jar`, removing every other connector bundle staged in that midPoint | doc: say so — the rm is deliberate (stale bundles after a rename) but invisible from this description`

`minor | docker/README.md:107 | "the purge removes any cn=test-* under o=data that does not carry the current run's id" is wider than the code: `EDirTestSupport.java:223-249` searches only the two configured containers, only for the two configured object classes, and re-checks the container suffix before deleting — `test.properties.example:39` states the narrow rule correctly | doc: match the narrow rule, which is also the reassuring one when the tests are pointed at a real directory`

`minor | docker/README.md:19 | "at minimum set EDIR_PASSWORD and MP_ADMIN_PASSWORD" contradicts :144 ("Its defaults describe this rig, so it works unedited"): `test.properties.example:33,62` hardcodes the two `.env.example` passwords, so following the setup instruction makes the live suites fail at `connector.test()` — and by design they fail rather than skip (`EDirTestSupport.java:186-192`) | doc: note that changing either password means changing `test.edir.bindPassword` / `test.midpoint.password` too`

`minor | .github/copilot-instructions.md:23 | "Selected via AbstractLdapConfiguration.setSearchStrategy() based on server capabilities detected at init" names a method that does not exist (the property is `pagingStrategy`, :846) and misplaces the decision: `AbstractLdapConnector.java:829-867` picks a strategy per search request, and `init()` deliberately opens no connection at all (:131-136) | doc`

`minor | .github/copilot-instructions.md:29 | "Strategy selected during connector init() based on configuration" is wrong for sync as well: `chooseSyncStrategy()` runs lazily on the first `sync()`/`getLatestSyncToken()` (`AbstractLdapConnector.java:1933`), which is why a bad `synchronizationStrategy` value surfaces as a task failure rather than a configuration error | doc`

`minor | .github/copilot-instructions.md:36 | "Key methods: getConnection(), releaseConnection(), getRootDseAttribute()" — `ConnectionManager` has no `releaseConnection`; the method is `returnConnection(LdapNetworkConnection)` (`connection/ConnectionManager.java:254`), which is what every caller in `sync/` and `search/` uses | doc`

`minor | .github/copilot-instructions.md:101 | "ErrorHandler (abstract) — Override for connector-specific LDAP error mapping … eDirectory maps specific error codes to ConnId exceptions" describes a subclass that does not exist: `ErrorHandler.java:24` is concrete and `EDirectoryLdapConnector.java:384` instantiates it directly with a comment saying eDirectory needs no special mapping; the constraint-violation handling the doc attributes to it lives in `processCreateResult`/`processModifyResult` (:172,:192) | doc`

`minor | .github/copilot-instructions.md:57 | "eDirectory ignores unsupported attrs, OpenLDAP applies PermissiveModify control" describes neither connector on this branch: there is no OpenLDAP variant left, and PermissiveModify is applied for any server whenever `usePermissiveModify` resolves true (`AbstractLdapConnector.java:491-508`) — a developer trusting this looks for server-conditional code that is not there | doc`

`minor | pom.xml:137 | the build comment states the connector deliberately avoids the SLF4J bridge ("bridge over a bridge is not a good idea"), while `src/main/resources/logging.properties:2` is packaged into the bundle and installs `org.slf4j.bridge.SLF4JBridgeHandler` as the JUL root handler — a class no dependency provides, so the file would fail if JUL ever loaded it | code: delete `logging.properties`, or doc: explain when it is meant to be used`

`minor | README.md:11 | the deployment step names the bundle jar but does not warn about `connector-edir-<version>-sources.jar`, which `maven-source-plugin` drops into the same `target/` and which ConnId tries to parse as a bundle and fails on at startup — `docker/README.md:76` and `docker/rig:96` both call this out, the user-facing instructions do not | doc: add the same warning here`

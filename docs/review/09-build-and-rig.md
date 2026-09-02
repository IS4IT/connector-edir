# 09 — Build and test rig

Unit: `pom.xml`, `src/main/assembly/connector.xml`, `docker/*`, `src/main/resources/*`.

The bundle itself is in good shape. I built the jar and read its manifest and contents:
`ConnectorBundle-Name` is `com.evolveum.polygon.connector-edir`, matching the artifactId,
the README's upgrade note, `TestMidPointIntegration.EXPECTED_BUNDLE` and the
`dependencytrack` profile — bundle identity is consistent everywhere it appears. `lib/`
holds 12 jars: `api-all` and its transitives, the re-added `mina-core` 2.2.9, and
`connector-common`; `connector-framework` is excluded by the assembly and `slf4j-api`
stays out via `provided`, both correct, and every remaining jar is either used directly
from `src/main/java` or a genuine `api-all` transitive. Nothing unnecessary is shipped.
All 72 `@ConfigurationProperty` accessors across `AbstractLdapConfiguration` and
`EDirectoryLdapConfiguration` have both a `.display` and a `.help` key in
`edirectory/Messages.properties`, so no configuration property renders as a raw key —
except the connector's own display name, which is the first finding below. On the rig, I
was suspicious that `cmd_seed`'s `up --exit-code-from` would take eDirectory down with it,
so I reproduced the pattern against the local daemon: under Compose 29.x
`--abort-on-container-exit` stops only the attached service and leaves the healthy
dependency running. That is not a bug. `docker/.env` is gitignored and `.env.example`
leaks nothing beyond throwaway credentials and a registry hostname the README already
publishes. Ports are bound to `127.0.0.1` throughout. The remaining findings are two
majors — a mislabelled connector in the midPoint UI and an SBOM upload that is a green
no-op — and a set of minors, mostly stale or dead files that will mislead the next person
to touch them.

## Findings

- `major | src/main/resources/com/evolveum/polygon/connector/ldap/edirectory/Messages.properties:17 | the catalog defines connector.ldap.display but @ConnectorClass uses displayNameKey="connector.ldap.edirectory.display"; ConnId's AbstractConnectorInfo.getConnectorDisplayName() falls back to ConnectorKey.getConnectorName(), so midPoint lists the connector as "com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector" instead of a name | rename the key to connector.ldap.edirectory.display and give it the eDirectory name`
- `major | pom.xml:216 | the dependencytrack profile binds upload-bom to verify but never runs makeBom (that lives in the separate sbom profile), and the mojo's failOnError defaults to false, so mvn verify -Pdependencytrack logs the missing target/bom.xml — and an unresolved ${env.DTRACK_URL}, which Maven leaves as a literal when the variable is unset — and still exits 0 with nothing uploaded | add the makeBom execution to the dependencytrack profile (or document that -Psbom,dependencytrack is mandatory) and set <failOnError>true</failOnError>`
- `minor | pom.xml:209 | parentName is connector-edir, which is also what projectName defaults to (${project.artifactId}), so the upload asks Dependency-Track to make the project its own parent | drop parentName, or name the real parent project and add the parentVersion it needs`
- `minor | pom.xml:179 | cyclonedx-maven-plugin carries no <version> and neither connector-parent nor polygon manages it, so Maven resolves whatever is newest in the configured repositories (2.9.1 on this machine) — the SBOM schema can change, or the build break, with no change to this repo | pin the version explicitly`
- `minor | src/main/resources/com/evolveum/polygon/connector/ldap/Messages.properties:1 | ConnId's LocalConnectorInfoManagerImpl.getBundleNamePrefixes() loads only <connector package>/Messages, so this file is never read at runtime; it has already drifted four AbstractLdapConfiguration properties behind (testMode, runAsStrategy, lastLoginDateAttribute, logSchemaErrors) and anyone adding a label next to the class it names gets no label in the UI | delete it and keep only the edirectory catalog (which still carries 17 dead AD/OpenLDAP prefixes such as globalCatalogServers and openLdapAccessLogDn, worth pruning at the same time)`
- `minor | src/main/resources/logging.properties:2 | names org.slf4j.bridge.SLF4JBridgeHandler as the JUL handler, but jul-to-slf4j is not a dependency and pom.xml:137-142 explicitly rejects that bridge, so the only way this file takes effect (-Djava.util.logging.config.file) produces "Can't load log handler ... ClassNotFoundException"; line 1 also uses // where .properties wants # | delete the file — it is otherwise dead weight at the root of the bundle jar`
- `minor | docker/docker-compose.yml:29 | the header tells you to build with "cp target/connector-*.jar docker/connectors/", a path that is neither bind-mounted nor present in the repo, so the jar never reaches midPoint and the connector looks undiscovered | replace with docker/rig deploy / docker compose cp, matching lines 275-280 and docker/README.md`
- `minor | docker/docker-compose.yml:228 | mp_init's "grep -q ERROR ... && ( run-sql ... ) || echo" exits 0 on both branches, so a failed repository or audit schema creation still satisfies mp_server's service_completed_successfully and midPoint starts against a half-created database | run the init script under set -e and drop the trailing || echo so a failed run-sql fails the container`
- `minor | docker/rig:69 | recover_prompt_race && waited=0 resets the deadline on every recovery, so a container that keeps hitting the prompt race never trips the 60-minute limit; the loop also never checks that edir is still running, so a container that exited during configuration produces an hour of dots instead of an error | compute the limit from an absolute start timestamp, and abort if docker compose ps edir no longer shows it running`
- `minor | docker/rig:33 | docker/.env is sourced by bash, which does not parse the same grammar as Compose's .env reader: a Compose-valid MP_ADMIN_PASSWORD=P@ss(1) aborts the rig with "syntax error near unexpected token", and a value containing $ or a backtick is expanded and then exported, overriding the file for Compose too | read only the handful of variables rig needs (grep/cut, or docker compose config), or document that .env values must be bash-safe rather than just alphanumeric for EDIR_PASSWORD`
- `minor | docker/README.md:48 | the service table says edir runs "Identity Vault only (no IDM engine, UA, OSP or SSPR)" while docker-compose.yml:65 deliberately sets INSTALL_ENGINE: "true" to get the DirXML-* schema, so the README misstates what is in the tree and why first boot is slow | correct the table row (line 12's "30 minutes" is also stale — start_period is 40m)`

No blockers.

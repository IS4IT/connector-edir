# Test rig

An eDirectory tree to run the connector against, plus a midPoint server to check that
the connector bundle is discovered and a resource can be configured against it.

## Prerequisites

- Docker with Compose v2.
- Access to the private registry holding the eDirectory image: `docker login hub.is4it.de`.
  The image is licensed — do not push derived images from this repository.
- The image is **linux/amd64 only**. On Apple Silicon it runs under emulation, and the
  first boot has to create the tree, which takes a while. The healthcheck allows 40
  minutes before calling the container unhealthy, and `docker/rig init` waits an hour.

## Setup

```bash
cp docker/.env.example docker/.env
$EDITOR docker/.env          # works unedited against this rig
```

If you do change `EDIR_PASSWORD` or `MP_ADMIN_PASSWORD`, change the matching
`test.edir.bindPassword` and `test.midpoint.password` in
`src/test/resources/test.properties` too — `test.properties.example` ships the values from
`.env.example`, and the live suites fail rather than skip when a reachable server rejects
the credentials.

Keep `EDIR_PASSWORD` alphanumeric. The image's `/startidm.sh` configures itself by
dumping the container environment with `env > file` and then `source`ing that file, so a
password containing spaces or shell metacharacters breaks the silent install in a way
the logs do not explain.

## Bring-up

Use the `docker/rig` wrapper. `docker compose up` on its own is not enough: the vendor
image restarts the Identity Vault part way through configuration, and intermittently
wedges on a "Could not find prompt ID" race that can only be cleared by truncating its log
and restarting the container. Neither is something a compose healthcheck can recover from.
`docker/rig` is a much reduced relative of `../idm-compose/idm-compose`, which solves the
same problems for the full IDM stack.

```bash
docker/rig init      # bring up, wait for the tree, seed o=data  (~10 min first time)
docker/rig seed      # re-create o=data on its own
docker/rig deploy    # build the connector and load it into midPoint
docker/rig status    # what is running, and how to reach it
docker/rig stop      # stop containers, keeping the tree
docker/rig down      # remove containers, keeping the tree
docker/rig wipe      # remove everything including the tree
docker/rig logs -f edir     # anything else is passed to docker compose
```

| Service | Purpose |
|---|---|
| `edir` | eDirectory, Identity Vault only (no IDM engine, UA, OSP or SSPR) |
| `edir_seed` | one-shot; creates `o=data` with `ou=users` and `ou=groups` |
| `mp_data` | PostgreSQL for midPoint |
| `mp_init` | one-shot; creates the midPoint repository and audit schema |
| `mp_server` | midPoint |

Endpoints (all bound to loopback only):

- LDAPS `ldaps://localhost:20636` — bind as `cn=admin,ou=sa,o=system`
- iMonitor `https://localhost:20830/nds`
- midPoint GUI `http://localhost:20080/midpoint` — `administrator` / `MP_ADMIN_PASSWORD`
- midPoint REST `http://localhost:20080/midpoint/ws/rest/`

## Ports

All eDirectory ports are set in `docker/.env` and default to the 20xxx range, so this rig
can run at the same time as `idm-compose`, which occupies 7389/7636/7828/7830. They are
published 1:1 — the container listens on the same numbers it is reached on, so the logs
and the test configuration agree.

## Testing a connector build

```bash
docker/rig deploy
```

That runs `mvn clean package`, removes every `connector-*.jar` already staged in
`/opt/midpoint/var/icf-connectors/`, copies the new bundle in and restarts midPoint. Both
destructive steps are deliberate — `clean` so a rename cannot leave two jars in `target/`,
the `rm` so a previous bundle name cannot linger — but note that the `rm` removes *any*
other connector bundle you may have staged in that midPoint by hand.

It skips `connector-*-sources.jar`, which `maven-source-plugin` also produces and which
ConnId would try to parse as a connector bundle and fail on at startup.

## No bind mounts

Every path in this rig is a named volume, and the seed LDIF is inlined in the compose
file rather than mounted. That is deliberate: the Docker context here is colima, whose VM
does not see every host path, and a bind mount of an unshared path does not fail — it
silently appears inside the container as an empty directory. `midpoint-diff/docker` and
`idm-compose` avoid bind mounts for the same reason.

Consequences worth knowing:

- The test tree definition lives in the `edir_seed` service's `command:`, not in a
  separate `.ldif` file.
- Connector jars are staged with `docker compose cp`, as above.

## Notes

- **Plaintext LDAP does not work, by design.** The image ends its setup with
  `ldapconfig set "Require TLS for Simple Binds with Password=yes"`, so simple binds on
  the plaintext port are refused. Tests use `connectionSecurity=ssl` against the LDAPS
  port. The rig's certificate is self-signed, so the tests and the resource template set
  `allowUntrustedSsl=true` explicitly — it is *not* the connector's default, which is
  `false`. Do not "fix" the plaintext port by flipping the TLS-for-simple-binds setting
  back.
- **Shut down cleanly.** `stop_grace_period` is 300s; killing eDirectory early can damage
  the DIB.

## Data lifecycle — read before you stop the rig

Test data is kept on purpose so a run can be inspected afterwards, in eDirectory and in
midPoint. Nothing is deleted at the end of a run; leftovers are removed at the *start* of
the next one. Objects are named `test-<purpose>-<runId>`, and the purge is deliberately
narrow: only the two configured containers, only the two configured object classes, only
names matching exactly that generated shape, and each candidate's DN is re-checked against
its container before anything is deleted. An object of your own that merely starts with
`test-` is left alone.

So after `mvn test` you can browse `o=data` over LDAPS or iMonitor, and look at the
resource, its shadows and the connector in the midPoint GUI. The midPoint resource has a
fixed OID and is replaced, not duplicated, on each run.

The tree itself lives in the `edir_config` volume and survives `docker compose down`.
The container's own scripts keep eDirectory's data under `/config/idm/eDirectory_data`
while still exposing it at the legacy path — `/var/opt/novell/eDirectory/data/dib` and
`/config/idm/eDirectory_data/data/dib` are the same inode — so the DIB, `nds.conf` and
the completion marker are all inside the volume. After a `down`, the next `docker/rig
init` comes back in seconds rather than rebuilding for ten minutes. Only `docker/rig
wipe` (`down -v`) discards the tree.

For the same reason, do **not** add a volume for `/var/opt/novell/eDirectory`. It
collides with that arrangement and makes `ndsconfig` fail with "Command socket error"
before it can create the DIB.

One sharp edge remains. eDirectory decides on start whether it is already configured by
looking for `/config/idm/version.properties`, which `/startidm.sh` copies in only after
configuration completes. **A container stopped before that point cannot be restarted** —
it comes back with "User credentials are invalid" and has to be wiped and rebuilt. So do
not interrupt the first `docker/rig init`; it waits for exactly that marker before
reporting the tree ready.

midPoint is likewise unaffected by `down`: its repository, home and connector directory
are all named volumes.

## Running the tests

The tests are configured by `src/test/resources/test.properties`, which is read off the
test classpath. Copy the template once:

```bash
cp src/test/resources/test.properties.example src/test/resources/test.properties
```

Its defaults describe this rig, so it works unedited. Then run the tests however you like —
`mvn test`, or straight from IntelliJ; there is nothing to configure in the IDE, which is
the point of keeping the settings in a file rather than in `-D` flags. A system property
still wins over the file for one-off overrides:

```bash
mvn test -Dtest.edir.port=1636
```

`test.properties` is gitignored, and the file is also how you point the tests at an
existing eDirectory or midPoint instead of the rig — see the comments in the template. The
two servers are independent, so an existing directory can be used with the rig's midPoint
and the other way round. That is what `test.midpoint.edir.host` is for: midPoint is inside
the compose network and resolves the `edir` service name, while the test JVM is on the host
and goes through the published port on loopback.

Both suites skip themselves when `test.properties` is absent **or** the servers it names
cannot be reached, so a plain `mvn test` stays green without a rig. A server that answers
and then refuses — a wrong password, say — fails the tests rather than skipping them.

Note that the midPoint suite needs the connector staged into midPoint first
(`docker/rig deploy`).

# connector-edir
Polygon/ConnId EDirectory Connector based on ApacheDS client SDK

This branch restores the EDirectoryLdapConnector, which was removed a few years ago. See https://docs.evolveum.com/connectors/connectors/com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector/ for the details.

In this branch, the generic LDAP connector and the AD connector are removed, so it can be build without duplicating them. We keep this in the same project, so cherry-picking commits for the common code base remains easy.

To use this connector to a MidPoint instance

* build the project in IntelliJ IDEA using the Maven "package" target
* copy `target/connector-edir-<version>.jar` to `/opt/midpoint/var/icf-connectors` on the MidPoint server and/or `/connid-connector-server/bundles/` on the ConnID Java Connector Server — that jar only, **not** the `-sources` jar `maven-source-plugin` leaves beside it, which ConnID tries to parse as a bundle and fails on at startup
* restart the MidPoint Server and/or ConnID Java Connector Server

There is a Docker test rig with eDirectory and MidPoint under `docker/`; see `docker/README.md`.

## Upgrading from the connector-ldap artifact id

This connector used to build as `connector-ldap`, which gave it the same ConnID bundle name
as Evolveum's own LDAP connector — the two could not be deployed to one MidPoint together.
It now builds as `connector-edir`, so the bundle name is
`com.evolveum.polygon.connector-edir` instead of `com.evolveum.polygon.connector-ldap`.

The Java package is unchanged, so `connectorType` is still
`com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector` and `connectorRef`
filters that match on it keep resolving. The configuration schema namespace does change,
because MidPoint builds it from the bundle name and the connector class. For every existing
resource:

1. Remove the old `connector-ldap-<version>.jar` from the connector directory and drop in
   `connector-edir-<version>.jar`.
2. **Delete the old `ConnectorType` object**, then restart MidPoint. This step is required,
   not just tidy-up: while a `ConnectorType` for `EDirectoryLdapConnector` still exists
   under the old bundle, MidPoint logs `Discovered ICF bundle in JAR:
   com.evolveum.polygon.connector-edir` but does not create a `ConnectorType` for it, and
   the new connector never becomes usable. After deleting it and restarting, the
   `connector-edir` bundle registers normally.
3. Rewrite the `<icfc:configurationProperties>` namespace in every eDirectory resource,
   from
   `.../icf-1/bundle/com.evolveum.polygon.connector-ldap/com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector`
   to the same URL with `connector-edir` in place of `connector-ldap`.
4. **Strip the `oid` attribute from each `connectorRef`.** MidPoint stores the OID it
   resolved the reference to, and step 2 gave the connector a new one, so a resource
   carrying the old OID no longer finds its connector. Exporting and re-importing does not
   fix this by itself — the export contains the stale `oid` alongside the filter. Removing
   the attribute lets the filter in step 5 resolve it again.
5. If a resource's `connectorRef` filter matches on `connectorType` alone, add
   `connectorBundle` to it. Both Evolveum's connector and this one can now be deployed
   together, so `connectorType` on its own is no longer unambiguous.

### Other properties this fork renamed

Independently of the bundle rename, this fork renamed the configuration property `timeout`
to `globalTimeout` (per-server `timeout=` inside the `servers` property is unchanged). A
resource carrying the old name fails on an unknown configuration property, so rename it in
the same pass.

Export the affected resources, rewrite them, re-import, and try it on a non-production
instance first.
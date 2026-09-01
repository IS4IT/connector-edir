# connector-edir
Polygon/ConnId EDirectory Connector based on ApacheDS client SDK

This branch restores the EDirectoryLdapConnector, which was removed a few years ago. See https://docs.evolveum.com/connectors/connectors/com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector/ for the details.

In this branch, the generic LDAP connector and the AD connector are removed, so it can be build without duplicating them. We keep this in the same project, so cherry-picking commits for the common code base remains easy.

To use this connector to a MidPoint instance

* build the project in IntelliJ IDEA using the Maven "package" target
* copy `target/connector-edir-<version>.jar` to `/opt/midpoint/var/icf-connectors` on the MidPoint server and/or `/connid-connector-server/bundles/` on the ConnID Java Connector Server
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
4. If a resource's `connectorRef` filter matches on `connectorType` alone, add
   `connectorBundle` to it. Both Evolveum's connector and this one can now be deployed
   together, so `connectorType` on its own is no longer unambiguous.

Export the affected resources, rewrite them, re-import, and try it on a non-production
instance first.
# connector-ldap
Polygon/ConnId EDirectory Connector based on ApacheDS client SDK

This branch restores the EDirectoryLdapConnector, which was removed a few years ago. See https://docs.evolveum.com/connectors/connectors/com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector/ for the details.

In this branch, the generic LDAP connector and the AD connector are removed, so it can be build without duplicating them. We keep this in the same project, so cherry-picking commits for the common code base remains easy.

To use this connector to a MidPoint instance

* build the project in IntelliJ IDEA using the Maven "package" target
* copy `target/connector-ldap-<version>.jar` to `/opt/midpoint/var/icf-connectors` on the MidPoint server and/or `/connid-connector-server/bundles/` on the ConnID Java Connector Server
* restart the MidPoint Server and/or ConnID Java Connector Server
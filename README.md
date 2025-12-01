# connector-ldap
Polygon/ConnId LDAP Connector based on ApacheDS client SDK

Restored EDirectoryLdapConnector, which was removed a few years ago. See https://docs.evolveum.com/connectors/connectors/com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector/ for the details.

To use this connector to a MidPoint instance

* build the project in IntelliJ IDEA using the Maven "package" target
* copy `target/connector-ldap-<version>.jar` to `/opt/midpoint/var/icf-connectors` on the MidPoint server and/or `/connid-connector-server/bundles/` on the ConnID Java Connector Server
* restart the MidPoint Server and/or ConnID Java Connector Server

Three new connector versions should become available for LDAP, AD and Edirectory.
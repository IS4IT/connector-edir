# connector-ldap
Polygon/ConnId LDAP Connector based on ApacheDS client SDK

Restored EDirectoryLdapConnector, which was removed a few years ago. See https://docs.evolveum.com/connectors/connectors/com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector/ for the details.

To use this connector to a MidPoint instance

* build the project and export as `connector-ldap-2.3.jar`
* copy `connector-ldap-2.3.jar` to `/opt/midpoint/var/icf-connectors` on the MidPoint server
* restart the MidPoint Server

Three new connectors should become available: v2.3 of LDAP, AD and Edirectory.
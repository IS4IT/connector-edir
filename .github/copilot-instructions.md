# AI Coding Agent Instructions for LDAP Connector

## Project Overview
**LDAP Connector** is a ConnID/Polygon LDAP connector for MidPoint identity management. It provides LDAP, Active Directory, and eDirectory support through pluggable implementations. The project follows a template-method pattern with `AbstractLdapConnector` as the base and specialized subclasses for each directory type.

**Build**: Maven Java 17 | **Framework**: Apache Directory API, IdentityConnectors Framework | **Deployment**: JAR to MidPoint/ConnID

## Critical Architecture Patterns

### 1. **Connector Hierarchy & Strategy Pattern**
- `AbstractLdapConnector` (2245 lines) - Core connector logic for CRUD, schema, sync operations
- `LdapConnector` - Generic LDAP/OpenLDAP/OpenDJ variant
- `EDirectoryLdapConnector` - Novell eDirectory variant
- Each subclass **must override**: `createSchemaTranslator()`, `createErrorHandler()`, `addServerSpecificConfigurationSuggestions()`
- Key config class: `AbstractLdapConfiguration` (1327 lines) - Shared properties like `host`, `port`, `connectionSecurity`, `baseContext`, `bindDn`

### 2. **Search Strategy (Polymorphic)**
Located in `/src/main/java/com/evolveum/polygon/connector/ldap/search`:
- `SearchStrategy` interface - Implements different LDAP pagination/search styles
- `DefaultSearchStrategy` - Simple search without paging
- `SimplePagedResultsSearchStrategy` - RFC2696 paging (most common)
- `VlvSearchStrategy` - Virtual List View (performance optimization)
- Selected via `AbstractLdapConfiguration.setSearchStrategy()` based on server capabilities detected at init

### 3. **Sync Strategy (Pluggable)**
Located in `/src/main/java/com/evolveum/polygon/connector/ldap/sync`:
- `SyncStrategy` interface - Change detection mechanism
- `ModifyTimestampSyncStrategy` - Uses `modifyTimestamp` attribute for incremental sync
- Strategy selected during connector `init()` based on configuration

### 4. **Connection Management**
`src/main/java/com/evolveum/polygon/connector/ldap/connection/ConnectionManager.java`:
- `ConnectionManager` - Acquires/releases `LdapNetworkConnection` (Apache Directory API)
- `ServerConnectionPool` - Connection pooling with failover to secondary server
- `ServerDefinition` - Encodes host/port/SSL settings
- Key methods: `getConnection()`, `releaseConnection()`, `getRootDseAttribute()` for server detection

### 5. **Schema Translation**
`src/main/java/com/evolveum/polygon/connector/ldap/schema/AbstractSchemaTranslator.java`:
- Maps LDAP schema (objectClasses, attributes) ↔ ConnID schema (`ObjectClass`, `Attribute` objects)
- `ReferenceAttributeTranslator` - Handles DN-based references (e.g., `member` in groups)
- `AssociationHolder` - Represents association pairs (e.g., `"user"+memberOf = "group"+members`)
- Key pattern: **LDAP objectClass → ConnID ObjectClass**, **LDAP attribute → ConnID Attribute**

## Critical Data Flows

### Search/List Operation
1. ConnID calls `AbstractLdapConnector.search()` with filter + handler
2. Filter translated via `LdapFilterTranslator` (ConnID `EqualsFilter` → LDAP RFC4515)
3. `SearchStrategy` implementation executes via `ConnectionManager` → `LdapNetworkConnection`
4. Results converted to ConnID `ConnectorObject` via schema translator
5. Handler processes each object (lazy evaluation for large result sets)

### Modify Operation
1. ConnID calls `AbstractLdapConnector.addAttributeModification()` with `AttributeDelta`
2. Generate LDAP `Modification` objects (`ADD`, `REPLACE`, `DELETE` operations)
3. Connector-specific logic: eDirectory ignores unsupported attrs, OpenLDAP applies `PermissiveModify` control
4. Submit via LDAP MODIFY to connection manager

### Sync (Change Detection)
1. Connector implements `SyncOp` interface
2. `ModifyTimestampSyncStrategy.getLatestSyncToken()` queries `modifyTimestamp` values
3. Incremental queries retrieve changed objects since last token
4. Returns `SyncDelta` objects with `ADD`/`MODIFY`/`DELETE` change types

## Development Workflows

### Build & Test
```bash
# Package JAR for deployment
mvn clean package

# Run specific test with LDAP server properties
mvn -Dtest=TestAD \
  -Dtest.ad.host="server.com" \
  -Dtest.ad.port=636 \
  -Dtest.ad.connectionSecurity="ssl" \
  -Dtest.ad.baseContext="CN=Users,DC=example,DC=com" \
  -Dtest.ad.bindDn="CN=ServiceAccount..." \
  -Dtest.ad.bindPassword="..." test
```

### Memory Requirements
- Surefire args: `-Xms1024m -Xmx4096m --add-exports java.management/sun.management=ALL-UNNAMED`
- Large result sets use streaming via `SearchCursor` to avoid heap exhaustion

## Project-Specific Conventions

### Configuration Constants
- Stored in `AbstractLdapConfiguration` with `CONF_PROP_NAME_*` constants (e.g., `CONF_PROP_NAME_HOST`)
- Delimiter for associations: `"-#"` (e.g., `"user"+memberOf -#- "group"+members`)
- Scopes: `"sub"` (subtree), `"one"` (one level), `"base"` (single entry)

### Server Detection Pattern
Used in `src/main/java/com/evolveum/polygon/connector/ldap/LdapConnector.java` to auto-config:
- Check `rootDSE` objectClass for `"OpenLDAP"` → apply OpenLDAP settings
- Check vendor version for `"OpenDJ"` → apply OpenDJ settings
- Populate `SuggestedValues` for UI configuration hints

### Error Handling
- `ErrorHandler` (abstract) - Override for connector-specific LDAP error mapping
- Example: eDirectory maps specific error codes to ConnID exceptions (e.g., `ERR_CONSTRAINT_VIOLATION` → `InvalidAttributeValueException`)
- Wrap Apache Directory API exceptions (`LdapException`, `CursorException`) as ConnID exceptions

### Logging
- Use `org.identityconnectors.common.logging.Log` (NOT Log4j directly)
- Usage: `private static final Log LOG = Log.getLog(ClassName.class);`
- Operational logs via `OperationLog` class (tracks connector operations)

## Common Entry Points for Modifications

1. **Add new server variant**: Extend `AbstractLdapConnector`, override `createSchemaTranslator()`, `createErrorHandler()`
2. **Support new LDAP attribute**: Add to `AbstractLdapConfiguration` property + schema translator mapping
3. **Fix server-specific behavior**: Extend `ErrorHandler` or add conditional logic in connector subclass
4. **Optimize search**: Implement custom `SearchStrategy` for advanced pagination
5. **Change sync mechanism**: Create new `SyncStrategy` (e.g., USN-based for AD)

## Cross-File Dependencies
- Configuration changes → Update schema translator mapping
- Schema changes → Validate filter translator handles new attribute types
- Connection pool changes → Test failover scenarios via `ServerConnectionPool`
- Sync strategy changes → Ensure token format compatibility with incremental searches

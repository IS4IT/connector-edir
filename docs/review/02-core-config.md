# 02 - Core configuration

`AbstractLdapConfiguration` is a plain ConnId configuration bean: ~60 `@ConfigurationProperty`
getters, a `validate()` that checks three things, and a `recompute()` that fills in defaults and
derives the five per-operation timeouts from `globalTimeout`. The timeout derivation itself is
sound and idempotent (`recompute()` can run twice on the same bean without drift), and the
pre-3.3 compatibility branch that promotes a lone `connectTimeout` to the global timeout is
correct. The problems are elsewhere: the fork flipped `allowUntrustedSsl` to `true`, which
disables certificate validation for every deployment that does not override it while the
UI help text still tells the admin the default is `false`; `validate()` enforces almost nothing
of what the `required = true` annotations advertise and now unboxes a nullable `Integer port`;
and the `timeout` -> `globalTimeout` rename is missing from the README migration steps that
otherwise cover the bundle move in detail. `LdapConstants.java` is a flat constant holder with
one wrong constant value (reported below); nothing else in it fails at runtime.

## Findings

- `blocker | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:99 | allowUntrustedSsl defaults to true (flipped from upstream false in commit 2869853), so every deployment that does not explicitly set it gets ServerConnectionPool.createTrustManager():449 installing NoVerificationTrustManager for both "ssl" and "starttls" — bind DN, bind password and password changes travel over an encrypted but unauthenticated channel, and the property help text the admin reads (src/main/resources/.../edirectory/Messages.properties:38) still states "If set to false (which is default and recommended)" | restore the default to false so untrusted certificates are an explicit opt-in, and add a note to the README upgrade section, since resources migrated per the current instructions silently lose certificate validation.`

- `major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:1246 | now that port is Integer, "port < 0" auto-unboxes: a configuration whose port was cleared makes ConnectorFacade.validate() throw a raw NullPointerException instead of the intended port.illegalValue ConfigurationException, and the same null reaches ServerDefinition.copyAllFromConfiguration():111 where it is assigned to an int field — ServerDefinition.parse():148-152 already guards exactly this case for the multi-server path, so the single-server path is the odd one out | null-check port in validate() (throw port.illegalValue) and default it back to DEFAULT_PORT in recompute() so no downstream unboxing can fail.`

- `major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:132 | the timeout -> globalTimeout rename (commit c7fc196) is not mentioned in the README "Upgrading from the connector-ldap artifact id" steps, which otherwise spell out the namespace rewrite element by element; a resource migrated exactly as documented keeps <timeout> in icfc:configurationProperties, which no longer exists in the connector schema, so midPoint rejects the resource configuration after the admin believes the migration is complete | add a rename step to the README list, and either keep a deprecated timeout alias delegating to globalTimeout or align ServerDefinition.parse():161, which still reads the per-server key as "timeout".`

- `minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:179 | checkAliveTimeout is read nowhere in the connector (only its own getter) and AbstractLdapConnector.checkAlive():2197 is a deliberate no-op that always "passes", so an operator tuning connection liveness gets no effect at all — checkAliveRootDse and referralStrategy are both flagged OBSOLETE in their help texts, this one is not | mark it obsolete in the javadoc and in both Messages.properties bundles, or drop the property.`

- `minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:585 | CONF_PROP_NAME_AUX_OBJECT_CLASSES holds "attributesNotReturnedByDefault", a copy/paste of the constant declared 12 lines above, so the first code that uses it (a discovery suggestion key, a validation message) will silently address the wrong configuration property with no compile-time signal | set the value to "auxiliaryObjectClasses".`

- `minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:1274 | host.blank, port.illegalValue and baseContext.invalidDn exist in neither Messages.properties bundle, and ConnId's ConnectorMessagesImpl.format(key, null) returns the key itself when unresolved, so an operator who leaves the host empty sees a ConfigurationException whose entire message is the literal string "host.blank" | add the three keys to both Messages.properties files.`

- `minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:1249 | validate() rejects a blank baseContext but silently accepts a null one, and a null baseContext becomes the empty DN in ServerDefinition.createDefaultDefinition():88 (new Dn((String) null) yields the root DSE, verified against api-ldap-model 2.1.0), so a resource with the field cleared searches and synchronizes the entire DIT instead of failing configuration validation | validate baseContext unconditionally, or annotate it required = true, now that the DiscoverConfigurationOp that used to suggest it has been removed.`

- `minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:663 | bindDn and bindPassword are annotated required = true but validate() checks neither, and ConnId treats required purely as UI metadata; with both unset ServerConnectionPool.bind():519-530 builds an empty DN with no credentials and performs an anonymous bind, which eDirectory accepts as [Public], so the connector reports a successful connection and then fails every read with empty results and every write with insufficient rights | add validateNotBlank(bindDn, ...) and a null check on bindPassword to validate() so the misconfiguration is reported where the operator is looking.`

- `minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:928 | usePermissiveModify and useTreeDelete are the only string-enum properties in the class without allowedValues in @ConfigurationProperty and validate() does not check them either, so midPoint renders free-text fields; a typo survives validate() and the connection test and then AbstractLdapConnector.isUsePermissiveModify():502 / isUseTreeDelete():528 throw ConfigurationException on the first update or delete in production | add allowedValues = { ..._NEVER, ..._AUTO, ..._ALWAYS } to both, matching every other enum-valued property here.`

- `minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConfiguration.java:1158 | getMemberOfAllowedValues() hands out the live internal array and AbstractSchemaTranslator.shouldValueBeIncluded():1791-1795 writes a derived value back through setMemberOfAllowedValues() during a search, mutating the configuration long after validate()/recompute(); with filterOutMemberOfValues = true and no baseContext it stores new String[]{ null } and every subsequent memberOf value throws NPE inside Predicate.not(String::isEmpty) | return a defensive copy from the getter and compute the baseContext fallback once in recompute() instead of at operation time.`

No blockers beyond the SSL default, and nothing in `LdapConstants.java` fails at runtime — the
one defect that touches it (`CONF_PROP_NAME_AUX_OBJECT_CLASSES`) lives in the configuration class.

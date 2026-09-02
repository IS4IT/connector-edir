# 08 — Tests

`src/test/java/com/evolveum/polygon/connector/ldap/{TestEDirectory,TestMidPointIntegration,EDirTestSupport,TestLdapUtil}.java`,
`src/test/resources/{midpoint/resource-edir.xml,test.properties.example}`

The gating design holds up: settings really do come off the classpath, the TCP probe really is
narrower than a `connector.test()` and cannot swallow a bad bind password, and the purge search is
genuinely scoped — `OperationOptions.setContainer` reaches
`AbstractLdapConnector.getBaseDn`, which uses the container DN as the search base, so the LDAP
search cannot return anything outside it. The damage potential is one level in from there: the
purge decides *which* of the returned entries is "ours" from the bare `cn=test-` prefix, so on a
directory that was not created by these tests it will delete pre-existing objects that merely share
the prefix, and its second-line containment check is a string `endsWith` rather than a DN
comparison, which silently disarms the purge for legal container spellings. The midPoint suite's
regex JSON reader takes the first field match in a window that starts 2000 characters *before* the
anchor, so it reads a neighbouring connector's fields — which both false-alarms the bundle-identity
assertion and makes the duplicate-bundle guard pass vacuously. `test150Unlock` asserts a value that
a never-locked account reports anyway. I did not treat the rig passwords in
`test.properties.example` as a finding on their own — the file is a template for a throwaway
loopback rig and is not the copy the tests read — but the transport those credentials travel over
is a finding. `TestLdapUtil` is fine and, ironically, already covers exactly the DN comparison the
purge should be using.

## Findings

- `blocker | src/test/java/com/evolveum/polygon/connector/ldap/EDirTestSupport.java:261 | the purge treats every object whose RDN starts with cn=test- as a leftover, so pointing the tests at a real directory that already contains e.g. cn=test-account-migration,ou=users,o=data deletes it irrecoverably on the first run, with purgePreviousRuns defaulting to true | match the shape the tests actually generate — cn=test-<purpose>-<13 digits>, e.g. a regex anchored on the first RDN with a trailing epoch-millis group — and purge only when that group is present and differs from RUN_ID, instead of accepting the bare prefix`

- `major | src/test/java/com/evolveum/polygon/connector/ldap/EDirTestSupport.java:245 | the container check is a lowercased string endsWith, not a DN comparison: a legal container value with a space after the comma (test.edir.usersContainer=ou=users, o=data) makes every candidate fail it, so the purge deletes nothing while logging "Refusing to delete ..." — a warning that reads exactly like a scope violation; conversely an entry whose RDN carries an escaped comma (cn=test-x\,ou=users directly under o=data) satisfies the suffix test while being outside the container | compare DNs, not strings: LdapUtil.isDescendantOf(LdapUtil.asDn(dn), LdapUtil.asDn(containerDn)) — it is already in main and already covered by TestLdapUtil.testDnAncestor`

- `major | src/test/java/com/evolveum/polygon/connector/ldap/TestMidPointIntegration.java:353 | valueOf builds a window starting 2000 chars before the anchor and then returns the FIRST field match in it, so it reads whichever connector entry the window happens to open in — midPoint connector entries are a few hundred bytes each, so test000 fails with "Connector bundle name changed" quoting a neighbouring connector's bundle (a false alarm indistinguishable from the regression it exists to catch), and it only passes when our connector happens to be listed first | cut the window to the enclosing JSON object (scan back to the nearest '{' and forward to its matching '}') before running the field regex`

- `major | src/test/java/com/evolveum/polygon/connector/ldap/TestMidPointIntegration.java:205 | same root cause, opposite symptom: ourVersion is a neighbouring connector's version, so no entry in the loop can match EXPECTED_BUNDLE plus that version and the duplicate-bundle-identity guard — the whole point of the test — passes without ever asserting anything; the positional zip of three independent regex scans (lines 208-220) is fragile for the same reason, since one connector missing a field shifts every later index | fix valueOf as above and build a list of per-connector field maps by object, rather than zipping three flat match lists by index`

- `major | src/test/java/com/evolveum/polygon/connector/ldap/TestEDirectory.java:244 | test150Unlock asserts __LOCK_OUT__==false on an account that was never locked, and EDirectorySchemaTranslator.extendConnectorObject reports false for any entry without lockedByIntruder — so the assertion holds whether or not the two modifications reached eDirectory, and test020 already asserts the same value; a completely broken unlock path passes | lock the account first (write lockedByIntruder=TRUE plus a future loginIntruderResetTime over a raw Apache Directory API connection, since the connector hides those attributes), assert __LOCK_OUT__==true, then unlock through the connector and assert the flip to false`

- `major | src/test/java/com/evolveum/polygon/connector/ldap/TestMidPointIntegration.java:129 | the midPoint admin password goes out as HTTP Basic and the eDirectory bind password as <clearValue> in the resource XML (src/test/resources/midpoint/resource-edir.xml:67), over whatever test.midpoint.url says — test.properties.example configures plain http:// and the class javadoc invites pointing the suite at an existing midPoint, so a non-loopback target puts both passwords on the wire in the clear with nothing checking | reject a test.midpoint.url that is neither https nor loopback (throw from beforeClass rather than skip, so it cannot be missed), and say so in test.properties.example next to test.midpoint.url`

- `minor | src/test/java/com/evolveum/polygon/connector/ldap/TestMidPointIntegration.java:292 | resourceXml pastes property values straight into XML, so a bind password or bind DN containing & < or > produces malformed XML and test100 fails as an opaque "PUT ... returned 400" instead of naming the cause | XML-escape each substituted value before the replace`

- `minor | src/test/java/com/evolveum/polygon/connector/ldap/EDirTestSupport.java:141 | missingProperties gates on property(p) == null while every other reader here treats blank as absent, so a key present but empty — test.edir.port= — passes the gate and surfaces as NumberFormatException: For input string: "" from edirReachable instead of the intended SkipException, and an empty bindDn/bindPassword silently becomes an anonymous bind that connector.test() may well accept | filter on value == null || value.isBlank()`

- `minor | src/test/java/com/evolveum/polygon/connector/ldap/TestMidPointIntegration.java:132 | HttpClient.newHttpClient() sets no connect timeout and midPointReachable sets no request timeout, so a midPoint host that drops packets blocks the probe for the OS TCP timeout (~2 min) before skipping, while the eDirectory probe two lines further down uses 5 s | HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)) and a matching .timeout() on the probe request`

- `minor | src/test/java/com/evolveum/polygon/connector/ldap/TestMidPointIntegration.java:146 | this suite creates test-midpoint-<runId> accounts but never calls purgePreviousRuns, so running it alone (mvn test -Dtest=TestMidPointIntegration, the normal way to iterate on it) accumulates accounts and their midPoint shadows without bound — the "next run cleans up" lifecycle only exists in TestEDirectory | call EDirTestSupport.purgePreviousRuns(connector) at the end of this beforeClass too; the shared RUN_ID already makes it safe when both suites run in one JVM`

- `minor | src/test/java/com/evolveum/polygon/connector/ldap/TestEDirectory.java:199 | test120SearchByName compares the DN it composed from test.edir.usersContainer against the DN eDirectory returns with assertEquals, so a container written in different but equally legal case or spacing (OU=Users,O=Data) fails the test on a cosmetic difference — test160GroupMembership compares the same kind of value with containsIgnoreCase | use the case-insensitive comparison here as well, or compare LdapUtil.asDn(...) values`

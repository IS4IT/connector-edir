/*
 * Copyright (c) 2026 IS4IT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.polygon.connector.ldap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeDeltaBuilder;
import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.testng.AssertJUnit;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConfiguration;

/**
 * Integration tests for the eDirectory connector, run directly against ConnId
 * (no midPoint involved). Requires a live eDirectory; see docker/README.md for
 * the test rig.
 *
 * The tests skip themselves when the connection properties are absent, so a
 * plain `mvn test` stays green without a rig:
 *
 * <pre>
 * mvn test -Dtest.edir.host=127.0.0.1 \
 *          -Dtest.edir.port=20636 \
 *          -Dtest.edir.connectionSecurity=ssl \
 *          -Dtest.edir.bindDn='cn=admin,ou=sa,o=system' \
 *          -Dtest.edir.bindPassword=... \
 *          -Dtest.edir.baseContext=o=data
 * </pre>
 *
 * Each test creates and removes the objects it needs, so they do not depend on
 * execution order and can be run individually.
 */
public class TestEDirectory {

    /**
     * ConnId object class names are the LDAP object class names
     * ({@code AbstractSchemaTranslator.toIcfObjectClassType}), and these two are the
     * defaults in {@link EDirectoryLdapConfiguration}.
     */
    private static final ObjectClass OC_USER = new ObjectClass("inetOrgPerson");
    private static final ObjectClass OC_GROUP = new ObjectClass("groupOfNames");

    /** Strong enough not to trip an eDirectory password policy. */
    private static final String TEST_PASSWORD = "Qwe.123.Rty.456";

    private ConnectorFacade connector;
    private String usersContainer;
    private String groupsContainer;

    /**
     * Objects created here are left in the tree on purpose, so a run can be inspected
     * afterwards in eDirectory and in midPoint. The next run removes them; see
     * {@link EDirTestSupport#purgePreviousRuns}.
     */
    @BeforeClass
    public void beforeClass() {
        String[] missingProperties = EDirTestSupport.missingProperties(EDirTestSupport.PROPERTIES);
        if (missingProperties.length != 0) {
            throw new SkipException(
                    "Missing properties for eDirectory connection configuration: " + Arrays.toString(missingProperties));
        }

        String baseContext = System.getProperty(EDirTestSupport.PROPERTY_BASE_CONTEXT);
        usersContainer = "ou=users," + baseContext;
        groupsContainer = "ou=groups," + baseContext;

        connector = EDirTestSupport.createConnectorFacade();
        connector.test();

        EDirTestSupport.purgePreviousRuns(connector, OC_GROUP, OC_USER);
    }

    /**
     * The schema translation has to survive every object class eDirectory reports,
     * including the ones using Novell-proprietary syntaxes under
     * {@code 2.16.840.1.113719.1.1.5.1}. A syntax the translator does not recognise
     * would surface here as an attribute with no type.
     */
    @Test
    public void test000Schema() {
        Schema schema = connector.schema();
        AssertJUnit.assertNotNull("No schema returned", schema);

        ObjectClassInfo userOci = findObjectClassInfo(schema, OC_USER);
        AssertJUnit.assertNotNull("No object class info for " + OC_USER, userOci);

        for (ObjectClassInfo oci : schema.getObjectClassInfo()) {
            for (AttributeInfo ai : oci.getAttributeInfo()) {
                AssertJUnit.assertNotNull(
                        "Attribute " + ai.getName() + " of object class " + oci.getType() + " has no type",
                        ai.getType());
            }
        }
    }

    /**
     * {@code EDirectorySchemaTranslator.extendObjectClassDefinition} adds __ENABLE__ and
     * __LOCK_OUT__ to user object classes, and {@code shouldTranslateAttribute} hides the
     * two eDirectory attributes they are built from.
     */
    @Test
    public void test010OperationalAttributesInSchema() {
        ObjectClassInfo userOci = findObjectClassInfo(connector.schema(), OC_USER);
        AssertJUnit.assertNotNull(userOci);

        AssertJUnit.assertNotNull("__ENABLE__ missing from " + OC_USER,
                findAttributeInfo(userOci, OperationalAttributes.ENABLE_NAME));
        AssertJUnit.assertNotNull("__LOCK_OUT__ missing from " + OC_USER,
                findAttributeInfo(userOci, OperationalAttributes.LOCK_OUT_NAME));

        AssertJUnit.assertNull("loginDisabled should be hidden, it is exposed as __ENABLE__",
                findAttributeInfo(userOci, "loginDisabled"));
        AssertJUnit.assertNull("lockedByIntruder should be hidden, it is exposed as __LOCK_OUT__",
                findAttributeInfo(userOci, "lockedByIntruder"));
    }

    /**
     * Covers {@code extendConnectorObject(ConnectorObjectBuilder, Entry, String)} — the
     * hook whose signature this fork changed relative to upstream. A freshly created
     * account has neither loginDisabled nor lockedByIntruder set, and the hook has to
     * synthesise __ENABLE__=true and __LOCK_OUT__=false from their absence.
     */
    @Test
    public void test020ExtendConnectorObject() {
        Uid uid = createUser("extend");

        ConnectorObject object = connector.getObject(OC_USER, uid, null);
        AssertJUnit.assertNotNull(object);

        assertSingleValue(object, OperationalAttributes.ENABLE_NAME, Boolean.TRUE);
        assertSingleValue(object, OperationalAttributes.LOCK_OUT_NAME, Boolean.FALSE);
    }

    @Test
    public void test100CreateAccount() {
        Uid uid = createUser("create");
        AssertJUnit.assertNotNull(uid);
        AssertJUnit.assertNotNull(uid.getUidValue());

        ConnectorObject object = connector.getObject(OC_USER, uid, null);
        AssertJUnit.assertNotNull("Created account cannot be read back", object);
        AssertJUnit.assertEquals(uid, object.getUid());
    }

    /**
     * __UID__ is GUID for this connector ({@code EDirectoryLdapConfiguration.recompute}),
     * which is a binary attribute — this exercises the binary identifier path in
     * {@code AbstractSchemaTranslator}.
     */
    @Test
    public void test110SearchByUid() {
        Uid uid = createUser("byuid");

        List<ConnectorObject> results = search(OC_USER, new EqualsFilter(uid));

        AssertJUnit.assertEquals("Wrong number of results searching by __UID__", 1, results.size());
        AssertJUnit.assertEquals(uid, results.get(0).getUid());
    }

    /** Searching by __NAME__ is translated into a base-scoped search on the DN. */
    @Test
    public void test120SearchByName() {
        String cn = uniqueName("byname");
        String dn = "cn=" + cn + "," + usersContainer;
        createUserWithCn(cn);

        List<ConnectorObject> results = search(OC_USER, new EqualsFilter(AttributeBuilder.build(Name.NAME, dn)));

        AssertJUnit.assertEquals("Wrong number of results searching by __NAME__", 1, results.size());
        AssertJUnit.assertEquals(dn, results.get(0).getName().getNameValue());
    }

    @Test
    public void test130ModifyAttribute() {
        Uid uid = createUser("modify");

        connector.updateDelta(OC_USER, uid,
                Set.of(AttributeDeltaBuilder.build("title", "Chief Testing Officer")),
                null);

        ConnectorObject object = connector.getObject(OC_USER, uid, null);
        assertSingleValue(object, "title", "Chief Testing Officer");
    }

    /**
     * __ENABLE__ is stored inverted, as eDirectory's loginDisabled
     * ({@code EDirectorySchemaTranslator.toLdapValue} negates it).
     */
    @Test
    public void test140DisableAndEnable() {
        Uid uid = createUser("disable");

        connector.updateDelta(OC_USER, uid,
                Set.of(AttributeDeltaBuilder.build(OperationalAttributes.ENABLE_NAME, Boolean.FALSE)),
                null);
        assertSingleValue(connector.getObject(OC_USER, uid, null), OperationalAttributes.ENABLE_NAME, Boolean.FALSE);

        connector.updateDelta(OC_USER, uid,
                Set.of(AttributeDeltaBuilder.build(OperationalAttributes.ENABLE_NAME, Boolean.TRUE)),
                null);
        assertSingleValue(connector.getObject(OC_USER, uid, null), OperationalAttributes.ENABLE_NAME, Boolean.TRUE);
    }

    /**
     * Unlocking clears lockedByIntruder and loginIntruderResetTime. Locking is
     * deliberately not supported — eDirectory sets the lock itself on failed binds.
     */
    @Test
    public void test150Unlock() {
        Uid uid = createUser("unlock");

        connector.updateDelta(OC_USER, uid,
                Set.of(AttributeDeltaBuilder.build(OperationalAttributes.LOCK_OUT_NAME, Boolean.FALSE)),
                null);
        assertSingleValue(connector.getObject(OC_USER, uid, null), OperationalAttributes.LOCK_OUT_NAME, Boolean.FALSE);
    }

    @Test
    public void test151LockIsRejected() {
        Uid uid = createUser("lock");

        try {
            connector.updateDelta(OC_USER, uid,
                    Set.of(AttributeDeltaBuilder.build(OperationalAttributes.LOCK_OUT_NAME, Boolean.TRUE)),
                    null);
            AssertJUnit.fail("Locking an account should be rejected, but it succeeded");
        } catch (RuntimeException e) {
            AssertJUnit.assertTrue(
                    "Expected an UnsupportedOperationException in the cause chain, got: " + e,
                    hasCause(e, UnsupportedOperationException.class));
        }
    }

    /**
     * Adding a member to a group has to write the reciprocal groupMembership attribute
     * on the member itself ({@code EDirectoryLdapConnector.updateGroupMemberShip}).
     *
     * That happens in {@code postUpdate}, so the membership has to be added by an update:
     * a group created with an initial member does not go through it. The group is created
     * with a throwaway member because groupOfNames requires at least one.
     */
    @Test
    public void test160GroupMembership() {
        Uid initialMemberUid = createUser("initial-member");
        Uid userUid = createUser("member");
        String userDn = dnOf(OC_USER, userUid);
        Uid groupUid = createGroup("grp", dnOf(OC_USER, initialMemberUid));
        String groupDn = dnOf(OC_GROUP, groupUid);

        connector.updateDelta(OC_GROUP, groupUid,
                Set.of(AttributeDeltaBuilder.build("member", List.of(userDn), List.of())),
                null);

        ConnectorObject user = connector.getObject(OC_USER, userUid, null);
        Attribute groupMembership = user.getAttributeByName("groupMembership");
        AssertJUnit.assertNotNull("groupMembership not set on the member", groupMembership);
        AssertJUnit.assertTrue(
                "groupMembership " + groupMembership.getValue() + " does not contain " + groupDn,
                containsIgnoreCase(groupMembership, groupDn));
    }

    @Test
    public void test170Password() {
        String cn = uniqueName("passwd");
        Set<Attribute> attributes = userAttributes(cn);
        attributes.add(AttributeBuilder.build(OperationalAttributes.PASSWORD_NAME,
                new GuardedString(TEST_PASSWORD.toCharArray())));

        Uid uid = connector.create(OC_USER, attributes, null);

        AssertJUnit.assertNotNull("Account with a password could not be created", uid);
        AssertJUnit.assertNotNull(connector.getObject(OC_USER, uid, null));
    }

    /** modifyTimestamp-based sync: a token taken before a create has to yield that create. */
    @Test
    public void test200Sync() {
        SyncToken token = connector.getLatestSyncToken(OC_USER);
        AssertJUnit.assertNotNull("No sync token returned", token);

        Uid uid = createUser("sync");

        List<SyncDelta> deltas = new ArrayList<>();
        connector.sync(OC_USER, token, delta -> {
            deltas.add(delta);
            return true;
        }, null);

        AssertJUnit.assertTrue("Created account " + uid + " did not show up in sync results",
                deltas.stream().anyMatch(d -> uid.equals(d.getUid())));
    }

    @Test
    public void test900Delete() {
        Uid uid = createUser("delete");

        connector.delete(OC_USER, uid, null);

        AssertJUnit.assertTrue("Account still found after delete",
                search(OC_USER, new EqualsFilter(uid)).isEmpty());
    }

    // ----------------------------------------------------------------- helpers

    private Uid createUser(String prefix) {
        return createUserWithCn(uniqueName(prefix));
    }

    private Uid createUserWithCn(String cn) {
        return connector.create(OC_USER, userAttributes(cn), null);
    }

    private Set<Attribute> userAttributes(String cn) {
        Set<Attribute> attributes = new HashSet<>();
        attributes.add(AttributeBuilder.build(Name.NAME, "cn=" + cn + "," + usersContainer));
        attributes.add(AttributeBuilder.build("cn", cn));
        // inetOrgPerson inherits the mandatory sn from person.
        attributes.add(AttributeBuilder.build("sn", cn));
        return attributes;
    }

    private Uid createGroup(String prefix, String memberDn) {
        String cn = uniqueName(prefix);
        Set<Attribute> attributes = new HashSet<>();
        attributes.add(AttributeBuilder.build(Name.NAME, "cn=" + cn + "," + groupsContainer));
        attributes.add(AttributeBuilder.build("cn", cn));
        attributes.add(AttributeBuilder.build("member", memberDn));

        return connector.create(OC_GROUP, attributes, null);
    }

    private String dnOf(ObjectClass objectClass, Uid uid) {
        return connector.getObject(objectClass, uid, null).getName().getNameValue();
    }

    private List<ConnectorObject> search(ObjectClass objectClass, EqualsFilter filter) {
        List<ConnectorObject> results = new ArrayList<>();
        connector.search(objectClass, filter, object -> {
            results.add(object);
            return true;
        }, null);
        return results;
    }

    private String uniqueName(String prefix) {
        return EDirTestSupport.testName(prefix);
    }

    private static ObjectClassInfo findObjectClassInfo(Schema schema, ObjectClass objectClass) {
        return schema.getObjectClassInfo().stream()
                .filter(oci -> objectClass.is(oci.getType()))
                .findFirst()
                .orElse(null);
    }

    private static AttributeInfo findAttributeInfo(ObjectClassInfo oci, String attributeName) {
        return oci.getAttributeInfo().stream()
                .filter(ai -> ai.getName().equalsIgnoreCase(attributeName))
                .findFirst()
                .orElse(null);
    }

    private static void assertSingleValue(ConnectorObject object, String attributeName, Object expected) {
        AssertJUnit.assertNotNull(object);
        Attribute attribute = object.getAttributeByName(attributeName);
        AssertJUnit.assertNotNull("Attribute " + attributeName + " not present on " + object.getName(), attribute);
        AssertJUnit.assertEquals("Wrong value of " + attributeName,
                expected, attribute.getValue().get(0));
    }

    private static boolean containsIgnoreCase(Attribute attribute, String expected) {
        return attribute.getValue().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

}

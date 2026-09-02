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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.APIConfiguration;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.QualifiedUid;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.test.common.TestHelpers;

import com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConfiguration;
import com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector;

/**
 * Shared settings for the tests that need a live eDirectory, so the property names, the
 * connector configuration and the cleanup rules exist in one place.
 *
 * <p>Settings come from {@code test.properties} on the test classpath — see
 * {@code test.properties.example}, and docker/README.md for the rig that the defaults
 * describe. Reading them off the classpath rather than from {@code -D} flags is what makes
 * the tests behave the same in an IDE as on the command line. A system property still wins
 * over the file, so a single value can be overridden for one run without editing anything.
 *
 * <p>The tests are not tied to the rig: point the properties at any eDirectory. They create
 * and delete users and groups in the two configured containers, so the bind user needs
 * write access there.
 */
final class EDirTestSupport {

    private static final Log LOG = Log.getLog(EDirTestSupport.class);

    private static final String CONFIG_FILE = "test.properties";

    /** Empty when the file is absent, which is what makes the suites skip. */
    private static final Properties FILE_PROPERTIES = loadConfigFile();

    /**
     * Objects created by the tests are named {@code test-<purpose>-<RUN_ID>}, and are
     * deliberately left behind so the tree can be inspected in eDirectory and midPoint
     * after a run. Leftovers are removed at the <em>start</em> of the next run instead,
     * by {@link #purgePreviousRuns}.
     *
     * <p>Computed once per JVM, so every suite in one surefire run shares it and no suite
     * purges another's objects.
     */
    static final String RUN_ID = Long.toString(System.currentTimeMillis());

    /** Marks an object as one of ours, and so as fair game for the purge. */
    static final String TEST_OBJECT_PREFIX = "test-";

    static final String PROPERTY_PREFIX = "test.edir.";

    static final String PROPERTY_HOST = PROPERTY_PREFIX + "host";
    static final String PROPERTY_PORT = PROPERTY_PREFIX + "port";
    static final String PROPERTY_CONNECTION_SECURITY = PROPERTY_PREFIX + "connectionSecurity";
    static final String PROPERTY_BIND_DN = PROPERTY_PREFIX + "bindDn";
    static final String PROPERTY_BIND_PASSWORD = PROPERTY_PREFIX + "bindPassword";
    static final String PROPERTY_BASE_CONTEXT = PROPERTY_PREFIX + "baseContext";

    static final String PROPERTY_USERS_CONTAINER = PROPERTY_PREFIX + "usersContainer";
    static final String PROPERTY_GROUPS_CONTAINER = PROPERTY_PREFIX + "groupsContainer";
    static final String PROPERTY_USER_OBJECT_CLASS = PROPERTY_PREFIX + "userObjectClass";
    static final String PROPERTY_GROUP_OBJECT_CLASS = PROPERTY_PREFIX + "groupObjectClass";
    static final String PROPERTY_TEST_PASSWORD = PROPERTY_PREFIX + "testPassword";
    static final String PROPERTY_PURGE = PROPERTY_PREFIX + "purgePreviousRuns";

    /** The settings with no sensible default; their absence is what skips the suites. */
    static final String[] PROPERTIES = {
            PROPERTY_HOST,
            PROPERTY_PORT,
            PROPERTY_CONNECTION_SECURITY,
            PROPERTY_BIND_DN,
            PROPERTY_BIND_PASSWORD,
            PROPERTY_BASE_CONTEXT
    };

    private EDirTestSupport() {
    }

    private static Properties loadConfigFile() {
        Properties properties = new Properties();
        try (InputStream in = EDirTestSupport.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                LOG.info("{0} not found on the test classpath - live tests will be skipped", CONFIG_FILE);
            } else {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + CONFIG_FILE + " from the test classpath", e);
        }
        return properties;
    }

    /** System property first, then {@code test.properties}; null when neither has it. */
    static String property(String name) {
        return System.getProperty(name, FILE_PROPERTIES.getProperty(name));
    }

    static String property(String name, String defaultValue) {
        String value = property(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    static boolean booleanProperty(String name, boolean defaultValue) {
        String value = property(name);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }

    /** Names of the settings that are not configured, sorted; empty when all are present. */
    static String[] missingProperties(String... properties) {
        return Arrays.stream(properties)
                .filter(p -> property(p) == null)
                .sorted()
                .toArray(String[]::new);
    }

    static String baseContext() {
        return property(PROPERTY_BASE_CONTEXT);
    }

    static String usersContainer() {
        return property(PROPERTY_USERS_CONTAINER, "ou=users," + baseContext());
    }

    static String groupsContainer() {
        return property(PROPERTY_GROUPS_CONTAINER, "ou=groups," + baseContext());
    }

    /** ConnId object class names are the LDAP ones; see AbstractSchemaTranslator.toIcfObjectClassType. */
    static ObjectClass userObjectClass() {
        return new ObjectClass(property(PROPERTY_USER_OBJECT_CLASS, "inetOrgPerson"));
    }

    static ObjectClass groupObjectClass() {
        return new ObjectClass(property(PROPERTY_GROUP_OBJECT_CLASS, "groupOfNames"));
    }

    /** Strong enough not to trip a default eDirectory password policy. */
    static String testPassword() {
        return property(PROPERTY_TEST_PASSWORD, "Qwe.123.Rty.456");
    }

    /** Name for a test object belonging to this run. */
    static String testName(String purpose) {
        return TEST_OBJECT_PREFIX + purpose + "-" + RUN_ID;
    }

    private static final int PROBE_TIMEOUT_MS = 5000;

    /**
     * Probes whether the configured eDirectory answers, so an unreachable server skips the
     * live tests instead of failing every one of them.
     *
     * <p>Deliberately a plain TCP connect rather than a connector {@code test()}: whether a
     * server is reachable is a transport question, and answering it at that level keeps the
     * probe from swallowing anything else. Going through the connector cannot make that
     * distinction — a wrong bind password surfaces as
     * {@code ConnectionFailedException: ERR_04170_TIMEOUT_OCCURED}, indistinguishable by
     * type from a server that is genuinely down, so a typo in a password would silently skip
     * the whole suite instead of failing it.
     *
     * <p>Callers should still run {@code connector.test()} afterwards, so a reachable but
     * misconfigured server fails loudly and early.
     */
    static boolean edirReachable() {
        String host = property(PROPERTY_HOST);
        int port = Integer.parseInt(property(PROPERTY_PORT));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            LOG.info("eDirectory at {0}:{1} not reachable ({2}) - live tests will be skipped",
                    host, port, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes test objects left over from earlier runs, so a run starts from a known state
     * while still leaving its own results in place for inspection afterwards. Groups go
     * first: removing a member's group before the member avoids dangling membership.
     *
     * <p>Scoped deliberately tightly, because these tests can be pointed at a real
     * directory. The search is restricted to the configured containers, and every candidate
     * is checked against that container again before it is deleted — a mis-set container
     * must not be able to widen this into a sweep of the whole base context. Set
     * {@code test.edir.purgePreviousRuns=false} to switch it off entirely.
     */
    static void purgePreviousRuns(ConnectorFacade connector) {
        if (!booleanProperty(PROPERTY_PURGE, true)) {
            LOG.info("{0} is false - leaving objects from earlier runs alone", PROPERTY_PURGE);
            return;
        }
        purgeContainer(connector, groupObjectClass(), groupsContainer());
        purgeContainer(connector, userObjectClass(), usersContainer());
    }

    private static void purgeContainer(ConnectorFacade connector, ObjectClass objectClass, String containerDn) {
        OperationOptions options = new OperationOptionsBuilder()
                // The connector reads this Uid as a DN, see AbstractLdapConnector.getBaseDn.
                .setContainer(new QualifiedUid(objectClass, new Uid(containerDn)))
                .build();

        List<ConnectorObject> objects = new ArrayList<>();
        connector.search(objectClass, null, object -> {
            objects.add(object);
            return true;
        }, options);

        String containerSuffix = "," + containerDn.toLowerCase();
        for (ConnectorObject object : objects) {
            String dn = object.getName().getNameValue();
            if (!isFromPreviousRun(dn)) {
                continue;
            }
            if (!dn.toLowerCase().endsWith(containerSuffix)) {
                // Belt and braces: the search should not have returned this at all.
                LOG.warn("Refusing to delete {0}: it is not inside {1}", dn, containerDn);
                continue;
            }
            try {
                connector.delete(objectClass, object.getUid(), null);
            } catch (RuntimeException e) {
                // Best effort: a stale object we cannot remove should not stop the run.
                LOG.warn("Could not remove leftover {0}: {1}", dn, e.getMessage());
            }
        }
    }

    private static boolean isFromPreviousRun(String dn) {
        String lower = dn.toLowerCase();
        return lower.startsWith("cn=" + TEST_OBJECT_PREFIX) && !lower.contains(RUN_ID);
    }

    static ConnectorFacade createConnectorFacade() {
        APIConfiguration impl =
                TestHelpers.createTestConfiguration(EDirectoryLdapConnector.class, createConfiguration());
        return ConnectorFacadeFactory.getInstance().newInstance(impl);
    }

    static EDirectoryLdapConfiguration createConfiguration() {
        EDirectoryLdapConfiguration config = new EDirectoryLdapConfiguration();
        config.setHost(property(PROPERTY_HOST));
        config.setPort(Integer.valueOf(property(PROPERTY_PORT)));
        config.setConnectionSecurity(property(PROPERTY_CONNECTION_SECURITY));
        config.setBindDn(property(PROPERTY_BIND_DN));
        config.setBindPassword(new GuardedString(property(PROPERTY_BIND_PASSWORD).toCharArray()));
        config.setBaseContext(baseContext());
        config.setUserObjectClass(userObjectClass().getObjectClassValue());
        config.setGroupObjectClass(groupObjectClass().getObjectClassValue());
        config.setPagingStrategy(AbstractLdapConfiguration.PAGING_STRATEGY_SPR);
        config.setSynchronizationStrategy(AbstractLdapConfiguration.SYNCHRONIZATION_STRATEGY_MODIFY_TIMESTAMP);
        // eDirectory commonly serves a self-signed certificate, the rig included. This is
        // also the connector's own default, so it doubles as a check that it still holds.
        config.setAllowUntrustedSsl(true);
        return config;
    }
}

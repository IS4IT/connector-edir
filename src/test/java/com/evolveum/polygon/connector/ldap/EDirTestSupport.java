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
import java.util.List;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.APIConfiguration;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.test.common.TestHelpers;

import com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConfiguration;
import com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector;

/**
 * Shared configuration for the tests that need a live eDirectory, so the property names
 * and the connector configuration exist in one place. See docker/README.md for the rig.
 */
final class EDirTestSupport {

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

    /** Name for a test object belonging to this run. */
    static String testName(String purpose) {
        return TEST_OBJECT_PREFIX + purpose + "-" + RUN_ID;
    }

    /**
     * Deletes test objects left over from earlier runs, so a run starts from a known
     * state while still leaving its own results in place for inspection afterwards.
     * Groups go first: removing a member's group before the member avoids leaving
     * dangling membership behind.
     */
    static void purgePreviousRuns(ConnectorFacade connector, ObjectClass... objectClasses) {
        for (ObjectClass objectClass : objectClasses) {
            List<ConnectorObject> objects = new ArrayList<>();
            connector.search(objectClass, null, object -> {
                objects.add(object);
                return true;
            }, null);

            for (ConnectorObject object : objects) {
                if (isFromPreviousRun(object.getName().getNameValue())) {
                    try {
                        connector.delete(objectClass, object.getUid(), null);
                    } catch (RuntimeException e) {
                        // Best effort: a stale object we cannot remove should not stop the run.
                    }
                }
            }
        }
    }

    private static boolean isFromPreviousRun(String dn) {
        String lower = dn.toLowerCase();
        return lower.startsWith("cn=" + TEST_OBJECT_PREFIX) && !lower.contains(RUN_ID);
    }

    /** Names of the properties that are not set, sorted; empty when everything is present. */
    static String[] missingProperties(String... properties) {
        return Arrays.stream(properties)
                .filter(p -> System.getProperty(p) == null)
                .sorted()
                .toArray(String[]::new);
    }

    static ConnectorFacade createConnectorFacade() {
        APIConfiguration impl =
                TestHelpers.createTestConfiguration(EDirectoryLdapConnector.class, createConfiguration());
        return ConnectorFacadeFactory.getInstance().newInstance(impl);
    }

    static EDirectoryLdapConfiguration createConfiguration() {
        EDirectoryLdapConfiguration config = new EDirectoryLdapConfiguration();
        config.setHost(System.getProperty(PROPERTY_HOST));
        config.setPort(Integer.valueOf(System.getProperty(PROPERTY_PORT)));
        config.setConnectionSecurity(System.getProperty(PROPERTY_CONNECTION_SECURITY));
        config.setBindDn(System.getProperty(PROPERTY_BIND_DN));
        config.setBindPassword(new GuardedString(System.getProperty(PROPERTY_BIND_PASSWORD).toCharArray()));
        config.setBaseContext(System.getProperty(PROPERTY_BASE_CONTEXT));
        config.setPagingStrategy(AbstractLdapConfiguration.PAGING_STRATEGY_SPR);
        config.setSynchronizationStrategy(AbstractLdapConfiguration.SYNCHRONIZATION_STRATEGY_MODIFY_TIMESTAMP);
        // The rig's eDirectory serves a self-signed certificate. This is also the
        // connector's own default, so it doubles as a check that it still holds.
        config.setAllowUntrustedSsl(true);
        return config;
    }
}

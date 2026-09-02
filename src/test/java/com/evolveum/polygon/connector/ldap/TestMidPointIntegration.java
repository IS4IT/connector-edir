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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.testng.AssertJUnit;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * End-to-end tests through midPoint's REST API: the connector bundle has to be
 * discovered under the identity this project builds it with, and a resource configured
 * against it has to reach eDirectory.
 *
 * This is the suite that will notice a bundle rename. midPoint derives the connector
 * configuration schema namespace from the bundle name and the connector class, so
 * changing the Maven artifactId invalidates the namespace in every existing resource —
 * {@link #test000ConnectorIsDiscovered()} asserts the deployed connector still matches
 * what {@code resource-edir.xml} declares.
 *
 * <p>Needs a midPoint with this connector deployed, and an eDirectory for it to talk to.
 * Both are configured through {@code test.properties} on the test classpath — copy
 * {@code test.properties.example} and edit it. The defaults describe the docker rig (see
 * docker/README.md), but existing servers work just as well. The suite skips itself when
 * the file is absent or either server cannot be reached.
 *
 * <p>It creates or replaces one resource, at {@code test.midpoint.resourceOid}, and one
 * account in eDirectory.
 *
 * <p>Note that midPoint has to reach eDirectory by its own route, which is not this JVM's:
 * with the rig, midPoint resolves the {@code edir} service name inside the compose network
 * while the tests go through the published port on loopback. That is what
 * {@code test.midpoint.edir.host} is for; it defaults to {@code test.edir.host} when both
 * sides use the same address.
 */
public class TestMidPointIntegration {

    private static final String PROPERTY_URL = "test.midpoint.url";
    private static final String PROPERTY_USER = "test.midpoint.user";
    private static final String PROPERTY_PASSWORD = "test.midpoint.password";

    /**
     * How midPoint reaches eDirectory, which is not how this JVM reaches it: midPoint
     * runs inside the compose network and resolves the {@code edir} service name, while
     * the test JVM runs on the host and goes through the published port on loopback.
     * Optional — defaults to {@code test.edir.host} / {@code test.edir.port} for the case
     * where both sides use the same address.
     */
    private static final String PROPERTY_RESOURCE_HOST = "test.midpoint.edir.host";
    private static final String PROPERTY_RESOURCE_PORT = "test.midpoint.edir.port";

    private static final String[] MIDPOINT_PROPERTIES = { PROPERTY_URL, PROPERTY_USER, PROPERTY_PASSWORD };

    private static final String CONNECTOR_TYPE =
            "com.evolveum.polygon.connector.ldap.edirectory.EDirectoryLdapConnector";

    /**
     * Must match {@code ${bundle}} as substituted into resource-edir.xml, and the
     * {@code ConnectorBundle-Name} the build produces — which connector-parent derives as
     * {@code ${project.groupId}.${project.artifactId}}. Change this when the artifact is
     * renamed; the failure of {@link #test000ConnectorIsDiscovered()} is the reminder
     * that every deployed resource needs its namespace migrated too.
     */
    private static final String EXPECTED_BUNDLE = "com.evolveum.polygon.connector-edir";

    private static final String NAMESPACE_PREFIX =
            "http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/";

    /**
     * OID of the resource this suite creates. Fixed so a re-run replaces it instead of
     * accumulating copies, and configurable via {@code test.midpoint.resourceOid} so it
     * cannot collide with anything in a midPoint that is not the rig.
     */
    private static final String PROPERTY_RESOURCE_OID = "test.midpoint.resourceOid";
    private static final String DEFAULT_RESOURCE_OID = "c0ffee00-1111-4222-8333-000000000001";

    private HttpClient http;
    private String baseUrl;
    private String authorization;
    private String resourceOid;

    private ObjectClass ocUser;
    private ConnectorFacade connector;

    @BeforeClass
    public void beforeClass() {
        String[] missing = EDirTestSupport.missingProperties(
                concat(MIDPOINT_PROPERTIES, EDirTestSupport.PROPERTIES));
        if (missing.length != 0) {
            throw new SkipException("Missing settings for the midPoint integration: " + Arrays.toString(missing)
                    + " - copy test.properties.example to test.properties to run these tests");
        }

        baseUrl = EDirTestSupport.property(PROPERTY_URL).replaceAll("/+$", "");
        authorization = "Basic " + Base64.getEncoder().encodeToString(
                (EDirTestSupport.property(PROPERTY_USER) + ":" + EDirTestSupport.property(PROPERTY_PASSWORD))
                        .getBytes(StandardCharsets.UTF_8));
        // Without a connect timeout a midPoint host that drops packets blocks the reachability
        // probe for the OS TCP timeout -- around two minutes -- before deciding to skip.
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        resourceOid = EDirTestSupport.property(PROPERTY_RESOURCE_OID, DEFAULT_RESOURCE_OID);
        ocUser = EDirTestSupport.userObjectClass();

        if (!midPointReachable()) {
            throw new SkipException("midPoint at " + baseUrl + " is not reachable");
        }
        if (!EDirTestSupport.edirReachable()) {
            throw new SkipException("eDirectory at " + EDirTestSupport.property(EDirTestSupport.PROPERTY_HOST)
                    + " is not reachable");
        }

        connector = EDirTestSupport.createConnectorFacade();
        // Reachable, so anything wrong from here on is configuration and must fail, not skip.
        connector.test();

        // This suite creates accounts too. Without this, running it on its own — the normal way
        // to iterate on it — accumulates them and their MidPoint shadows without bound, because
        // the "next run cleans up" lifecycle lived only in TestEDirectory. The shared RUN_ID
        // makes this safe when both suites run in one JVM.
        EDirTestSupport.purgePreviousRuns(connector);
    }

    /**
     * Releases the connector's sockets and MINA threads. Without it {@code dispose()} and the
     * whole {@code ConnectionManager.close()} chain have no test coverage at all, and each suite
     * leaves a bound eDirectory connection open for the rest of the surefire JVM.
     */
    @AfterClass(alwaysRun = true)
    public void afterClass() {
        if (connector != null) {
            ConnectorFacadeFactory.getInstance().dispose();
        }
    }

    /**
     * Only a transport-level failure counts as unreachable, so a midPoint that answers and
     * rejects — a wrong password, say — still fails the tests loudly rather than being
     * quietly skipped. Mirrors {@link EDirTestSupport#edirReachable}.
     */
    private boolean midPointReachable() {
        try {
            http.send(requestBuilder("/ws/rest/connectors").GET().build(), HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted probing " + baseUrl, e);
        }
    }

    /**
     * The bundle identity midPoint sees has to be the one resource-edir.xml was written
     * against. Both halves matter: the bundle name feeds the configuration schema
     * namespace, and the version is half of the key ConnId uses to reject duplicates.
     */
    @Test
    public void test000ConnectorIsDiscovered() throws Exception {
        String connectors = get("/ws/rest/connectors");

        String bundle = valueOf(connectors, CONNECTOR_TYPE, "connectorBundle");
        AssertJUnit.assertNotNull(
                "Connector " + CONNECTOR_TYPE + " was not discovered by midPoint. "
                        + "Was the jar staged into /opt/midpoint/var/icf-connectors and mp_server restarted?",
                bundle);
        AssertJUnit.assertEquals("Connector bundle name changed", EXPECTED_BUNDLE, bundle);

        String namespace = valueOf(connectors, CONNECTOR_TYPE, "namespace");
        AssertJUnit.assertEquals(
                "Configuration schema namespace does not match the one resource-edir.xml declares",
                NAMESPACE_PREFIX + EXPECTED_BUNDLE + "/" + CONNECTOR_TYPE, namespace);
    }

    /**
     * ConnId keys connector bundles on name plus version and refuses to load two that
     * collide ({@code WorkingBundleInfo.ensureBundlesAreUnique}). This fork used to ship as
     * {@code com.evolveum.polygon.connector-ldap}, the same bundle name as the
     * connector-ldap Evolveum bundles into the midPoint image, with only the version suffix
     * keeping the two jars apart. Renaming the artifact to connector-edir gave it an
     * identity of its own; this guards that it stays that way.
     *
     * A bundle legitimately contains several connector classes — Evolveum's exposes both
     * LdapConnector and AdLdapConnector at one name and version — so the invariant is not
     * that the pair is globally unique. It is that no connector this jar does <em>not</em>
     * provide shows up under this jar's identity.
     */
    @Test
    public void test010BundleIdentityIsNotSharedWithAnotherJar() throws Exception {
        String connectors = get("/ws/rest/connectors");

        String ourVersion = valueOf(connectors, CONNECTOR_TYPE, "connectorVersion");
        AssertJUnit.assertNotNull("Connector " + CONNECTOR_TYPE + " was not discovered", ourVersion);

        List<String> types = allValuesOf(connectors, "connectorType");
        List<String> bundles = allValuesOf(connectors, "connectorBundle");
        List<String> versions = allValuesOf(connectors, "connectorVersion");
        AssertJUnit.assertEquals("Malformed connector list", types.size(), bundles.size());
        AssertJUnit.assertEquals("Malformed connector list", types.size(), versions.size());

        for (int i = 0; i < types.size(); i++) {
            if (EXPECTED_BUNDLE.equals(bundles.get(i)) && ourVersion.equals(versions.get(i))) {
                AssertJUnit.assertEquals(
                        "Connector " + types.get(i) + " claims this jar's bundle identity ("
                                + EXPECTED_BUNDLE + ":" + ourVersion + "). Another connector jar is "
                                + "deployed under the same name and version; ConnId rejects that.",
                        CONNECTOR_TYPE, types.get(i));
            }
        }
    }

    /** Imports the resource and asks midPoint to test it, which reaches eDirectory. */
    @Test
    public void test100ImportResourceAndTestConnection() throws Exception {
        put("/ws/rest/resources/" + resourceOid, resourceXml());

        String result = post("/ws/rest/resources/" + resourceOid + "/test", null);

        String status = firstValueOf(result, "status");
        AssertJUnit.assertEquals("Resource test did not succeed: " + result, "success", status);
    }

    /**
     * Searching shadows with a resourceRef and an objectClass goes out to the resource,
     * so this exercises the whole chain: REST, midPoint, ConnId, connector, eDirectory.
     */
    @Test(dependsOnMethods = "test100ImportResourceAndTestConnection")
    public void test110SearchShadows() throws Exception {
        // Left in the tree afterwards, like everything else the tests create, so the
        // resulting shadow can be inspected in midPoint.
        String cn = EDirTestSupport.testName("midpoint");
        String dn = "cn=" + cn + "," + EDirTestSupport.usersContainer();
        createFixtureUser(cn, dn);

        String query = """
                <q:query xmlns:q="http://prism.evolveum.com/xml/ns/public/query-3">
                  <q:filter>
                    <q:and>
                      <q:ref><q:path>resourceRef</q:path><q:value oid="%s"/></q:ref>
                      <q:equal><q:path>objectClass</q:path>
                        <q:value xmlns:ri="http://midpoint.evolveum.com/xml/ns/public/resource/instance-3"\
                        >ri:inetOrgPerson</q:value>
                      </q:equal>
                    </q:and>
                  </q:filter>
                </q:query>
                """.formatted(resourceOid);

        String shadows = post("/ws/rest/shadows/search", query);

        AssertJUnit.assertTrue(
                "Account " + dn + " created through the connector did not come back as a shadow",
                shadows.contains(dn));
    }

    // ----------------------------------------------------------------- helpers

    private void createFixtureUser(String cn, String dn) {
        Set<Attribute> attributes = new HashSet<>();
        attributes.add(AttributeBuilder.build(Name.NAME, dn));
        attributes.add(AttributeBuilder.build("cn", cn));
        attributes.add(AttributeBuilder.build("sn", cn));
        connector.create(ocUser, attributes, null);
    }

    private String resourceXml() throws IOException {
        String template;
        try (InputStream in = getClass().getResourceAsStream("/midpoint/resource-edir.xml")) {
            AssertJUnit.assertNotNull("resource-edir.xml not on the test classpath", in);
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return template
                .replace("${oid}", xml(resourceOid))
                .replace("${bundle}", xml(EXPECTED_BUNDLE))
                .replace("${host}", xml(propertyOrSetting(PROPERTY_RESOURCE_HOST, EDirTestSupport.PROPERTY_HOST)))
                .replace("${port}", xml(propertyOrSetting(PROPERTY_RESOURCE_PORT, EDirTestSupport.PROPERTY_PORT)))
                .replace("${connectionSecurity}", xml(EDirTestSupport.property(EDirTestSupport.PROPERTY_CONNECTION_SECURITY)))
                .replace("${bindDn}", xml(EDirTestSupport.property(EDirTestSupport.PROPERTY_BIND_DN)))
                .replace("${bindPassword}", xml(EDirTestSupport.property(EDirTestSupport.PROPERTY_BIND_PASSWORD)))
                .replace("${baseContext}", xml(EDirTestSupport.baseContext()));
    }

    /**
     * A bind DN or password containing &amp;, &lt; or &gt; would otherwise produce malformed XML,
     * and the PUT fails as an opaque HTTP 400 that names nothing.
     */
    private static String xml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Value of {@code name}, falling back to the value of a different setting. */
    private static String propertyOrSetting(String name, String fallbackName) {
        return EDirTestSupport.property(name, EDirTestSupport.property(fallbackName));
    }

    private String get(String path) throws Exception {
        return send(requestBuilder(path).GET().build(), 200);
    }

    private String put(String path, String body) throws Exception {
        // 201 on create, 200 or 204 when it replaces an existing object.
        return send(requestBuilder(path)
                .header("Content-Type", "application/xml")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), 200, 201, 202, 204);
    }

    private String post(String path, String body) throws Exception {
        HttpRequest.Builder builder = requestBuilder(path);
        if (body == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/xml")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return send(builder.build(), 200, 201, 202, 204);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authorization)
                .header("Accept", "application/json");
    }

    private String send(HttpRequest request, int... acceptableStatuses) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        boolean ok = Arrays.stream(acceptableStatuses).anyMatch(s -> s == response.statusCode());
        AssertJUnit.assertTrue(
                request.method() + " " + request.uri() + " returned " + response.statusCode()
                        + ": " + abbreviate(response.body()),
                ok);
        return response.body();
    }

    /**
     * Reads a sibling field out of the JSON object that contains the given connectorType.
     * A regex is enough here — midPoint's connector list is flat, and pulling in a JSON
     * parser purely for the tests is not worth it.
     */
    private static String valueOf(String json, String connectorType, String field) {
        int anchor = json.indexOf('"' + connectorType + '"');
        if (anchor < 0) {
            return null;
        }
        // The fields of one connector are emitted together; search both directions from
        // the connectorType so field order does not matter.
        String window = json.substring(Math.max(0, anchor - 2000),
                Math.min(json.length(), anchor + 2000));
        return firstValueOf(window, field);
    }

    private static String firstValueOf(String json, String field) {
        Matcher m = fieldPattern(field).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> allValuesOf(String json, String field) {
        List<String> values = new ArrayList<>();
        Matcher m = fieldPattern(field).matcher(json);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    private static Pattern fieldPattern(String field) {
        return Pattern.compile('"' + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    private static String[] concat(String[] first, String[] second) {
        String[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}

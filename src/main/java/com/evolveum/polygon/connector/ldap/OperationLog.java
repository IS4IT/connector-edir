/**
 * Copyright (c) 2016 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.polygon.connector.ldap;

import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.identityconnectors.common.logging.Log;

/**
 * @author semancik
 *
 */
public class OperationLog {

    static final Log LOG = Log.getLog(OperationLog.class);

    /**
     * Utility wrapper around the connector logging framework that centralizes
     * operation-related logging for LDAP connections.
     *
     * <p>This class adds two conveniences compared to using the raw {@code LOG}
     * instance directly:
     * <ul>
     *   <li>Connection-aware methods automatically prefix messages with
     *       connection metadata (via {@link LdapUtil#formatConnectionInfo(LdapNetworkConnection)}),
     *       which makes it easy to correlate logs with a specific LDAP connection.</li>
     *   <li>Centralized methods provide a single place to control formatting,
     *       gating and future routing of operation logs (for example turning
     *       them into structured events) without changing call sites.</li>
     * </ul>
     *
     * <p>Use the connection-aware methods when the log message is tied to a
     * particular {@link org.apache.directory.ldap.client.api.LdapNetworkConnection}
     * (typical for request/response logging). Use {@link #log(String, Object...)}
     * and {@link #error(String, Object...)} for generic messages not associated
     * with a connection.
     *
     * @since 1.0
     */

    public static void logOperationReq(LdapNetworkConnection connection, String format, Object... params) {
        if (LOG.isInfo()) {
            // Logs an outgoing request tied to a specific LDAP connection.
            // The connection metadata is prepended to ease tracing in multi-connection scenarios.
            LOG.info(LdapUtil.formatConnectionInfo(connection) + " " + format, params);
        }
    }

    public static void logOperationRes(LdapNetworkConnection connection, String format, Object... params) {
        if (LOG.isInfo()) {
            // Logs a response or result corresponding to a previously issued request.
            // Semantically separated from request logging to make it clear whether
            // a message describes an outgoing request or an incoming response.
            LOG.info(LdapUtil.formatConnectionInfo(connection) + " " + format, params);
        }
    }

    public static void logOperationErr(LdapNetworkConnection connection, String format, Object... params) {
        if (LOG.isError()) {
            // Logs an error condition specific to an LDAP connection. This uses
            // the error log level and also prefixes the message with connection info.
            LOG.error(LdapUtil.formatConnectionInfo(connection) + " " + format, params);
        }
    }

    public static void log(String format, Object... params) {
        // Generic informational log without connection context. Use when the
        // message is not specific to a particular LDAP connection.
        LOG.info(format, params);
    }

    public static void error(String format, Object... params) {
        // Generic error log without connection context.
        LOG.error(format, params);
    }

    public static boolean isLogOperations() {
        // Indicates whether operation-level info logging is enabled. Callers may
        // use this to avoid expensive message construction when operation logs
        // are not enabled.
        return LOG.isInfo();
    }

}

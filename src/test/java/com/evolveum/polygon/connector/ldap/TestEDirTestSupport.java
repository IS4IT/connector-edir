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

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Unit tests for the part of {@link EDirTestSupport} that decides what the purge deletes.
 *
 * <p>This needs no server. It exists because the live suites can be pointed at an existing
 * eDirectory, which makes {@code isFromPreviousRun} the most destructive line in the repository:
 * anything it accepts is deleted without confirmation on the next run.
 */
public class TestEDirTestSupport {

    /** An object this run created; the next run should remove it. */
    @Test
    public void testMatchesOurOwnObjectFromAnEarlierRun() {
        AssertJUnit.assertTrue(EDirTestSupport.isFromPreviousRun("cn=test-create-1788275559078,ou=users,o=data"));
        AssertJUnit.assertTrue(EDirTestSupport.isFromPreviousRun("cn=test-initial-member-1788275559078,ou=users,o=data"));
        AssertJUnit.assertTrue(EDirTestSupport.isFromPreviousRun("CN=TEST-CREATE-1788275559078,OU=Users,O=Data"));
    }

    /** Objects belonging to the run in progress must survive it. */
    @Test
    public void testDoesNotMatchThisRun() {
        AssertJUnit.assertFalse(EDirTestSupport.isFromPreviousRun(
                "cn=" + EDirTestSupport.testName("create") + ",ou=users,o=data"));
    }

    /**
     * The blocker this class exists for: a real directory's own objects that merely start with
     * "test-" must never be deleted. The previous implementation accepted every one of these.
     */
    @Test
    public void testDoesNotMatchForeignObjectsSharingThePrefix() {
        AssertJUnit.assertFalse(EDirTestSupport.isFromPreviousRun("cn=test-account-migration,ou=users,o=data"));
        AssertJUnit.assertFalse(EDirTestSupport.isFromPreviousRun("cn=test,ou=users,o=data"));
        AssertJUnit.assertFalse(EDirTestSupport.isFromPreviousRun("cn=test-user,ou=users,o=data"));
        // Trailing digits, but not an epoch-millis stamp.
        AssertJUnit.assertFalse(EDirTestSupport.isFromPreviousRun("cn=test-server-01,ou=users,o=data"));
        // Right shape, but not in the first RDN — this is somebody else's object.
        AssertJUnit.assertFalse(EDirTestSupport.isFromPreviousRun("cn=payroll,ou=test-create-1788275559078,o=data"));
    }

    @Test
    public void testHandlesMalformedInput() {
        AssertJUnit.assertFalse(EDirTestSupport.isFromPreviousRun(""));
    }
}

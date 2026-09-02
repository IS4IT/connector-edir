# Cross-cutting pass 12 — Error handling

## Adjudication: `ReconnectException` vs. the `referral` fall-through

**Both reviewers stated a true fact. Reviewer 2's finding stands; reviewer 1's finding stands only
in a much narrower form than claimed, and their headline conclusion is wrong.**

Facts, verified:

* `grep -rn "new ReconnectException" src/` returns nothing. `ReconnectException` is declared
  (`ReconnectException.java:9`) and tested for in four places
  (`AbstractLdapConnector.java:2177`, `DefaultSearchStrategy.java:160`,
  `SimplePagedResultsSearchStrategy.java:193`, `VlvSearchStrategy.java:294`) but is **never
  constructed anywhere in this fork**. All four `instanceof ReconnectException` branches are dead.
  Reviewer 1 is right about this.
* Reviewer 1's conclusion — "the retry loops can never iterate, making `maximumNumberOfAttempts`
  dead configuration" — is **false for the three search strategies**. Each of them has live
  `continue OUTER` / `continue` paths that do not go through `ReconnectException`:
  * `DefaultSearchStrategy.java:96` — `connectionReconnect(...)` on
    `LdapConnectionTimeOutException | InvalidConnectionException`, then `continue OUTER`.
  * `DefaultSearchStrategy.java:145` — the `REFERRAL` branch (see below).
  * `SimplePagedResultsSearchStrategy.java:120-122` and `VlvSearchStrategy.java:163-165` — same
    reconnect path, each with an explicit `incrementRetryAttempts()`.
  * `VlvSearchStrategy.java:273-284` — `BUSY` result code: `incrementRetryAttempts()`,
    reconnect, `continue`.
  `incrementRetryAttempts()` (`SearchStrategy.java:338-345`) therefore does fire and
  `maximumNumberOfAttempts` (default 10, `AbstractLdapConfiguration.java:199`) is live
  configuration in the search strategies.
* Reviewer 1's conclusion **is correct for `AbstractLdapConnector.searchSingleEntry`**. Its loop
  (`AbstractLdapConnector.java:2120-2191`) exits by `break`, `return`, or `throw` on every path
  except the dead `ReconnectException` branch at 2177-2179. The `while` is effectively an `if`,
  and `maximumNumberOfAttempts` has no effect there. (The one other fall-through — a response that
  is not a `SearchResultEntry` — never reaches the next iteration either, because the `finally`
  at 2185-2188 throws first; see finding 3.)
* Reviewer 2 is **correct**. At `DefaultSearchStrategy.java:145` the `REFERRAL` branch sets
  `referral`, logs "Ignoring referral", and has no `break`. Control falls out of the `if/else if`
  chain to the bottom of the `OUTER: while (true)` body and the identical search request is
  re-sent. Unlike the SPR and VLV strategies, `DefaultSearchStrategy` has no
  `if (responseResultCount == 0) break;` guard at the loop tail, so nothing stops it until
  `incrementRetryAttempts()` (line 80) exceeds `maximumNumberOfAttempts`.

**Verdict on the contradiction:** keep reviewer 2's finding as written. Rewrite reviewer 1's
finding to scope it to `searchSingleEntry` plus "reconnect-and-retry on a mid-operation LDAP error
is dead code"; drop the claim that `maximumNumberOfAttempts` is dead configuration.

## Verdict

The error-handling layer has one structural defect that dominates everything else: the connector
does not distinguish "the server said no such object" from "the server said no". `SearchCursorImpl.next()`
returns `false` for *every* `SearchResultDone`, whatever its result code (verified by disassembling
`api-ldap-client-api-2.1.0.jar`), and `searchSingleEntry` reads that `false` as "entry is not there"
and returns `null`. Every caller turns that `null` into `UnknownUidException`. So an eDirectory
`insufficientAccessRights`, `busy`, `unavailable`, `adminLimitExceeded` or `operationsError` on a
`resolveDn` search is reported to midPoint as "this account does not exist" — the precise failure
mode that gets shadows marked dead and accounts re-created on the next reconciliation. Secondary to
that, two `finally` blocks throw `ConnectorException("...indicates bug in LDAP connector")` over the
real exception, `DefaultSearchStrategy` re-delivers an entire result set up to ten times when the
server answers with a referral, and the connection log — the operator's only diagnostic channel for
these failures — emits nothing at all when the log level is set to ERROR or WARN. The `catch (Throwable)`
blocks in `delete`/`updateDelta`/`ldapRename` are the usual suspects and are real, but they are less
dangerous than the silent `null`s.

## Findings

blocker | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:2165 | `searchSingleEntry` returns `null` ("entry is not there") for *every* non-SUCCESS `SearchResultDone`, because `SearchCursorImpl.next()` returns `false` on any done message without inspecting the result code; `resolveDn` (2063-2064) then throws `UnknownUidException`, so an eDirectory `insufficientAccessRights`/`busy`/`unavailable`/`adminLimitExceeded` during update or delete tells midPoint the account no longer exists, the shadow is marked dead, and reconciliation re-creates the account | after `cursor.next()` returns `false`, read `cursor.getSearchResultDone()`; if it is non-null and its result code is neither `SUCCESS` nor `NO_SUCH_OBJECT`, throw `processLdapResult(...)` instead of returning `null`

blocker | src/main/java/com/evolveum/polygon/connector/ldap/search/DefaultSearchStrategy.java:145 | the `REFERRAL` result code has no `break`, so control reaches the end of `OUTER: while (true)` and the identical search is re-sent; every entry already returned is passed to `handleResult`/`handler.handle` again on each of the 10 attempts (midPoint receives each account up to 10 times) before the search dies with `ConnectorIOException("Maximum number of attempts exceeded")` — and eDirectory returns result code 10 routinely when the search base lives in a partition the contacted replica does not hold | in the `REFERRAL` branch call `setCompleteResultSet(false)` and `break`, matching the intent of the "Ignoring referral" log message

major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:2187 | the `finally` calls `LdapUtil.closeDoneCursor(cursor)`, which throws `ConnectorException("Closing search cursor that is not DONE (indicates bug in LDAP connector)")` whenever the cursor is not done — which is exactly the state after `cursor.next()` (2139) threw `LdapConnectionTimeOutException`; the new exception replaces the in-flight one, so a search timeout during `resolveDn` reaches midPoint as an untyped `ConnectorException` blaming the connector, with the real cause and the retryable `ConnectorIOException`/`ConnectionFailedException` classification discarded | use `LdapUtil.closeAbandonCursor(cursor)` in the `finally`, or wrap the `closeDoneCursor` call so it cannot throw over a pending exception

major | src/main/java/com/evolveum/polygon/connector/ldap/search/SimplePagedResultsSearchStrategy.java:321 | same masking in `finishSearch`, whose javadoc states "Most of the errors in this method are ignored": the `catch (LdapException e)` at 318 deliberately swallows, then the `finally` calls `closeDoneCursor` on the not-done cursor and throws `ConnectorException`, so a fully completed paged import — all entries already handed to midPoint — is reported to midPoint as a failed operation | use `closeAbandonCursor` here; `finishSearch` must not be able to throw

major | src/main/java/com/evolveum/polygon/connector/ldap/ReconnectException.java:9 | never constructed anywhere in the fork (the AD error handler that raised it was deleted), so the reconnect-and-retry recovery guarded by `instanceof ReconnectException` at `AbstractLdapConnector.java:2177`, `DefaultSearchStrategy.java:160`, `SimplePagedResultsSearchStrategy.java:193` and `VlvSearchStrategy.java:294` is dead: a stale-connection error that surfaces as a plain `LdapException` or a non-SUCCESS `LdapResult` is thrown straight at midPoint with no retry, and `searchSingleEntry`'s retry loop can never iterate | either have `ErrorHandler.processLdapResult` return `ReconnectException` for the result codes that mean "this connection is unusable" (`OPERATIONS_ERROR`, `UNAVAILABLE`, `BUSY`), or delete `ReconnectException` and the four dead branches so the missing retry is visible

major | src/main/java/com/evolveum/polygon/connector/ldap/connection/ConnectionManager.java:397 | when the root DSE has no `supportedControl` attribute, `parseSupportedControls` only logs a warning (line 424) and leaves the `supportedControls` field `null`, so `isControlSupported` dereferences `getSupportedControls().contains(oid)` and throws `NullPointerException`; the same `null` is dereferenced at `AbstractSchemaTranslator.java:140`, so a tightened eDirectory root-DSE ACL turns every `schema()` call into an NPE with no usable message | set `supportedControls = Collections.emptyList()` in the failure branch, or throw a `ConfigurationException` naming the root DSE read as the cause

major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:1235 | `catch (Throwable)` around `ldapUpdateAttempt(...)` with the name-hint DN swallows everything — `OutOfMemoryError`, `PermissionDeniedException`, `InvalidAttributeValueException` — logs at WARN and silently replays the *whole* update against the resolved DN; because `ldapUpdateAttempt` also runs `updateAssociationsAttempt`, which modifies group entries other than the target, a failure late in the first attempt re-applies the earlier group-membership modifies a second time | catch `RuntimeException` only, and only retry when the exception indicates a stale DN (`UnknownUidException`, `LdapNoSuchObjectException`); rethrow everything else

major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:1975 | same `catch (Throwable)` in `delete()`: a `PermissionDeniedException` on the hint-DN delete is downgraded to a WARN, `resolveDn` runs, and if that search then hits the `null`-means-not-found path of finding 1 the caller gets `UnknownUidException` — midPoint concludes the account was already gone and marks the shadow dead although the entry is still in the directory | restrict the catch to the stale-DN exceptions as above; the same applies to `ldapRename` at line 1262

major | src/main/java/com/evolveum/polygon/connector/ldap/ConnectionLog.java:68 | every `error*` method gates on `isError()` (`LOG.isError()`) but then writes with `LOG.info(...)`; with the connector logger at ERROR or WARN — the level an operator picks to watch for failures — the gate passes and the write is dropped, so bind failures, add/modify/delete errors and search errors leave no trace at all | log with `LOG.error(...)` in the `error*` methods (and `LOG.warn` in `errorTagged`), matching the guard

major | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:1122 | `create()` throws `UnknownUidException("Cannot re-reading entry to get UID, entry was not found")` when read-after-create returns nothing, but the `add` at line 1070 already succeeded; on eDirectory a container ACL that grants Create without Read of the new entry (or any of the result codes in finding 1) makes every create report a failure for an account that exists, so midPoint records no shadow and the next reconciliation either duplicates the account or loops on `alreadyExists` | throw a distinct exception that names the created DN and states the entry was created, or fall back to the DN as `Uid`; do not report a committed create as "not found"

minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:1080 | `connectionLog.success(connection, "add", dnStringFromName)` is written before the result code is checked at line 1084, so the connection log records a successful add for an add that failed; `modify` has the same ordering at line 1687 vs. the check at 1689 | move both `connectionLog.success(...)` calls after the `ResultCodeEnum.SUCCESS` check

minor | src/main/java/com/evolveum/polygon/connector/ldap/search/SearchStrategy.java:208 | `logSearchOperationDone` records `connectionLog.searchSuccess(...)` for any non-null `SearchResultDone`, including `REFERRAL`, `SIZE_LIMIT_EXCEEDED` and `INSUFFICIENT_ACCESS_RIGHTS`, so the connection log reports "search success: N entries returned" for searches the server rejected | branch on `searchResultDone.getLdapResult().getResultCode()` and call `connectionLog.searchError`/`searchWarning` for non-SUCCESS codes

minor | src/main/java/com/evolveum/polygon/connector/ldap/ConnectionLog.java:151 | the `searchError` format string is `"CONN {0} search error: {2} ({3} {4} {5}{6}): {6} entries returned"` while the arguments are `desc, message, base, scope, filter, tag, numEntries` — `{1}` (the exception message) is never printed, the base DN is printed where the error should be, and `{6}` appears twice; every logged search error is missing its reason | renumber the placeholders to `{1}`..`{6}`

minor | src/main/java/com/evolveum/polygon/connector/ldap/LdapUtil.java:278 | the condition is inverted in all three `logOperationError` overloads (278, 294, 314): the `additionalErrorMessage != null` branch uses the format string that has no `{3}` slot, and the `== null` branch uses the one that does — so the additional error message is never logged and the dead branch would print a literal "null"; `ErrorHandler.java:102` passes `connectorMessage` as that argument, which is never null, so the extra diagnostic is always discarded | swap the two branches, and pass the exception message rather than `connectorMessage` from `ErrorHandler:102`

minor | src/main/java/com/evolveum/polygon/connector/ldap/AbstractLdapConnector.java:1454 | `catch (UnknownUidException ei)` builds `new InvalidAttributeValueException("... original exception message: " + ei.getMessage())` without passing `ei` as the cause, so the stack trace showing which association search failed is lost; the neighbouring `catch (InvalidAttributeValueException e)` at 1434 also calls `e.getMessage().contains(...)` unguarded, which NPEs if the message is null | pass `ei` as the cause and null-check `e.getMessage()` before `contains`

minor | src/main/java/com/evolveum/polygon/connector/ldap/connection/ServerConnectionPool.java:562 | a non-SUCCESS bind result is rethrown as `new ConnectionFailedException(processedException.getMessage())` with no cause, so the `LdapResult` diagnostic classified by `errorHandler.processLdapResult` two lines earlier never reaches midPoint's stack trace — unlike the sibling path at line 548, which does pass the cause | pass `processedException` as the cause

minor | src/main/java/com/evolveum/polygon/connector/ldap/connection/ServerConnectionPool.java:461 | a truststore initialisation failure is reported as bare `ConnectionFailedException("Unable to create trust manager.")` with no cause, and the caught exception at line 459 is passed as a *format argument* to `LOG.error(String, Object...)` rather than as the throwable, so no stack trace is emitted either — the operator gets a five-word message and nothing else | `throw new ConnectionFailedException("Unable to create trust manager: " + e.getMessage(), e)` from inside the catch

minor | src/main/java/com/evolveum/polygon/connector/ldap/schema/AbstractSchemaTranslator.java:2021 | `catch (NoSuchAlgorithmException e) { throw new ConnectorException("Could not find MessageDigest algorithm: "+alg); }` drops the cause, so a JCE provider problem during password hashing is indistinguishable from a plain misconfigured `passwordHashAlgorithm` | pass `e` as the cause

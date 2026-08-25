package com.axonect.aee.template.baseapp.application.monitoring.connectivity;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the classifier has to get right is not the exception type but the reason tag
 * Grafana ends up grouping by, so every case here asserts the tag.
 */
class ConnectivityFailureClassifierTest {

    /** Stands in for Hikari's own exception: same simple name, no dependency to import. */
    static class SQLTransientConnectionException extends SQLException {
        SQLTransientConnectionException(String message, Throwable cause) {
            super(message, null, 0, cause);
        }
    }

    /** Stands in for org.springframework.dao.DataAccessResourceFailureException. */
    static class DataAccessResourceFailureException extends RuntimeException {
        DataAccessResourceFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Stands in for io.lettuce.core.RedisCommandTimeoutException. */
    static class RedisCommandTimeoutException extends RuntimeException {
        RedisCommandTimeoutException(String message) {
            super(message);
        }
    }

    private static void assertReason(ConnectivityFailureReason expected, Throwable throwable) {
        assertEquals(expected, ConnectivityFailureClassifier.classify(throwable));
    }

    @Test
    void classifiesRefusedConnections() {
        assertReason(ConnectivityFailureReason.CONNECTION_REFUSED,
                new ConnectException("Connection refused"));
        assertReason(ConnectivityFailureReason.CONNECTION_REFUSED,
                new SQLException("Listener refused the connection: ORA-12541: TNS:no listener"));
        assertReason(ConnectivityFailureReason.CONNECTION_REFUSED,
                new SQLException("IO Error: The Network Adapter could not establish the connection"));
    }

    @Test
    void classifiesUnreachableHosts() {
        // The message is only the hostname, so this has to be caught by type.
        assertReason(ConnectivityFailureReason.HOST_UNREACHABLE,
                new UnknownHostException("redis-cluster-headless.cluster-redis.svc.cluster.local"));
        assertReason(ConnectivityFailureReason.HOST_UNREACHABLE,
                new IOException("Temporary failure in name resolution"));
    }

    @Test
    void classifiesTimeouts() {
        assertReason(ConnectivityFailureReason.CONNECTION_TIMEOUT, new SocketTimeoutException("Read timed out"));
        assertReason(ConnectivityFailureReason.CONNECTION_TIMEOUT, new TimeoutException("probe deadline"));
        assertReason(ConnectivityFailureReason.CONNECTION_TIMEOUT,
                new RedisCommandTimeoutException("Command timed out after 2 second(s)"));
    }

    @Test
    void classifiesDroppedConnections() {
        assertReason(ConnectivityFailureReason.CONNECTION_CLOSED, new IOException("Connection reset by peer"));
        assertReason(ConnectivityFailureReason.CONNECTION_CLOSED,
                new SQLException("ORA-03113: end-of-file on communication channel"));
    }

    @Test
    void classifiesCredentialFailures() {
        assertReason(ConnectivityFailureReason.AUTHENTICATION_FAILED,
                new SQLException("ORA-01017: invalid username/password; logon denied"));
        assertReason(ConnectivityFailureReason.AUTHENTICATION_FAILED,
                new RuntimeException("NOAUTH Authentication required."));
    }

    @Test
    void classifiesTlsFailures() {
        assertReason(ConnectivityFailureReason.TLS_FAILURE,
                new SSLHandshakeException("PKIX path building failed"));
    }

    @Test
    void classifiesKafkaClusterProblems() {
        assertReason(ConnectivityFailureReason.BROKER_UNAVAILABLE,
                new RuntimeException("Topic dc-provisioning not present in metadata after 2000 ms."));
        assertReason(ConnectivityFailureReason.CONNECTION_TIMEOUT,
                new RuntimeException("Expiring 1 record(s) for dc-provisioning-0: 5000 ms has passed since batch creation"));
    }

    @Test
    void classifiesHikariPoolStarvation() {
        assertReason(ConnectivityFailureReason.POOL_EXHAUSTED,
                new SQLTransientConnectionException(
                        "AAAHikariPool - Connection is not available, request timed out after 10001ms.", null));
    }

    @Test
    void prefersTheRootCauseOverTheWrapperThatSurfacedIt() {
        // Spring wraps the pool timeout, the pool wraps the refused socket. The pool
        // timeout is how the failure surfaced; the refused socket is why it happened,
        // and that is what the reason tag has to say.
        Throwable failure = new DataAccessResourceFailureException(
                "Unable to acquire JDBC Connection",
                new SQLTransientConnectionException(
                        "AAAHikariPool - Connection is not available, request timed out after 10001ms.",
                        new ConnectException("Connection refused")));

        assertReason(ConnectivityFailureReason.CONNECTION_REFUSED, failure);
    }

    @Test
    void fallsBackToTheWrapperWhenNothingDeeperExplainsTheFailure() {
        Throwable failure = new DataAccessResourceFailureException(
                "Unable to acquire JDBC Connection",
                new SQLTransientConnectionException(
                        "AAAHikariPool - Connection is not available, request timed out after 10001ms.", null));

        assertReason(ConnectivityFailureReason.POOL_EXHAUSTED, failure);
    }

    @Test
    void leavesApplicationErrorsAlone() {
        // Business failures are counted against the dependency but must never mark it down.
        assertReason(ConnectivityFailureReason.APPLICATION_ERROR,
                new SQLException("ORA-00001: unique constraint (AAA.PK_USER) violated"));
        assertReason(ConnectivityFailureReason.APPLICATION_ERROR,
                new IllegalArgumentException("Session-Timeout attribute is not a number"));
        assertReason(ConnectivityFailureReason.APPLICATION_ERROR, new RuntimeException());
        assertReason(ConnectivityFailureReason.APPLICATION_ERROR, null);
    }

    @Test
    void survivesASelfReferencingCauseChain() {
        Throwable looping = new RuntimeException("business rule failed") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertReason(ConnectivityFailureReason.APPLICATION_ERROR, looping);
    }
}

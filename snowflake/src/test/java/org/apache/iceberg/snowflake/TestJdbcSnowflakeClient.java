/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.snowflake;

import static org.apache.iceberg.snowflake.JdbcSnowflakeClient.DATABASE_NOT_FOUND_ERROR_CODES;
import static org.apache.iceberg.snowflake.JdbcSnowflakeClient.SCHEMA_NOT_FOUND_ERROR_CODES;
import static org.apache.iceberg.snowflake.JdbcSnowflakeClient.TABLE_NOT_FOUND_ERROR_CODES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.iceberg.ClientPool;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.jdbc.JdbcClientPool;
import org.apache.iceberg.jdbc.UncheckedInterruptedException;
import org.apache.iceberg.jdbc.UncheckedSQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TestJdbcSnowflakeClient {
  @Mock private Connection mockConnection;
  @Mock private JdbcClientPool mockClientPool;
  @Mock private JdbcSnowflakeClient.QueryHarness mockQueryHarness;
  @Mock private ResultSet mockResultSet;

  private JdbcSnowflakeClient snowflakeClient;

  @SuppressWarnings("unchecked")
  @BeforeEach
  public void before() throws SQLException, InterruptedException {
    snowflakeClient = new JdbcSnowflakeClient(mockClientPool);
    snowflakeClient.setQueryHarness(mockQueryHarness);
    doAnswer(invocation -> ((ClientPool.Action) invocation.getArguments()[0]).run(mockConnection))
        .when(mockClientPool)
        .run(any(ClientPool.Action.class));
    doAnswer(
            invocation ->
                ((JdbcSnowflakeClient.ResultSetParser) invocation.getArguments()[2])
                    .parse(mockResultSet))
        .when(mockQueryHarness)
        .query(
            any(Connection.class),
            any(String.class),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            ArgumentMatchers.<String>any());
  }

  @Test
  public void testNullClientPoolInConstructor() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new JdbcSnowflakeClient(null))
        .withMessageContaining("JdbcClientPool must be non-null");
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testDatabaseExists() throws SQLException {
    when(mockResultSet.next()).thenReturn(true).thenReturn(false);
    when(mockResultSet.getString("database_name")).thenReturn("DB_1");
    when(mockResultSet.getString("name")).thenReturn("SCHEMA_1");

    assertThat(snowflakeClient.databaseExists(SnowflakeIdentifier.ofDatabase("DB_1"))).isTrue();

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SHOW SCHEMAS IN DATABASE IDENTIFIER(?) LIMIT 1"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq("DB_1"));
  }

  @Test
  public void testDatabaseDoesntExist() throws SQLException {
    when(mockResultSet.next())
        .thenThrow(new SQLException("Database does not exist", "2000", 2003, null))
        .thenThrow(
            new SQLException(
                "Database does not exist, or operation cannot be performed", "2000", 2043, null))
        .thenThrow(
            new SQLException("Database does not exist or not authorized", "2000", 2001, null));

    // Error code 2003
    assertThat(snowflakeClient.databaseExists(SnowflakeIdentifier.ofDatabase("DB_1"))).isFalse();

    // Error code 2043
    assertThat(snowflakeClient.databaseExists(SnowflakeIdentifier.ofDatabase("DB_1"))).isFalse();

    // Error code 2001
    assertThat(snowflakeClient.databaseExists(SnowflakeIdentifier.ofDatabase("DB_1"))).isFalse();
  }

  @Test
  public void testDatabaseFailureWithOtherException() throws SQLException {
    Exception injectedException = new SQLException("Some other exception", "2000", 2, null);
    when(mockResultSet.next()).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedSQLException.class)
        .isThrownBy(() -> snowflakeClient.databaseExists(SnowflakeIdentifier.ofDatabase("DB_1")))
        .withMessageContaining("Failed to check if database 'DATABASE: 'DB_1'' exists")
        .withCause(injectedException);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testDatabaseFailureWithInterruptedException()
      throws SQLException, InterruptedException {
    Exception injectedException = new InterruptedException("Fake interrupted exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedInterruptedException.class)
        .isThrownBy(() -> snowflakeClient.databaseExists(SnowflakeIdentifier.ofDatabase("DB_1")))
        .withMessageContaining("Interrupted while checking if database 'DATABASE: 'DB_1'' exists")
        .withCause(injectedException);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testSchemaExists() throws SQLException {
    when(mockResultSet.next())
        .thenReturn(true)
        .thenReturn(false)
        .thenReturn(true)
        .thenReturn(false);
    when(mockResultSet.getString("name")).thenReturn("DB1").thenReturn("SCHEMA1");
    when(mockResultSet.getString("database_name")).thenReturn("DB1");
    when(mockResultSet.getString("schema_name")).thenReturn("SCHEMA1");

    assertThat(snowflakeClient.schemaExists(SnowflakeIdentifier.ofSchema("DB1", "SCHEMA1")))
        .isTrue();

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SHOW SCHEMAS IN DATABASE IDENTIFIER(?) LIMIT 1"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq("DB1"));
    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SHOW TABLES IN SCHEMA IDENTIFIER(?) LIMIT 1"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq("DB1.SCHEMA1"));
  }

  @Test
  public void testSchemaDoesntExistNoSchemaFoundException() throws SQLException {
    when(mockResultSet.next())
        // The Database exists check should pass, followed by Error code 2003 for Schema exists
        .thenReturn(true)
        .thenReturn(false)
        .thenThrow(new SQLException("Schema does not exist", "2000", 2003, null))
        // The Database exists check should pass, followed by Error code 2043 for Schema exists
        .thenReturn(true)
        .thenReturn(false)
        .thenThrow(
            new SQLException(
                "Schema does not exist, or operation cannot be performed", "2000", 2043, null))
        // The Database exists check should pass, followed by Error code 2001 for Schema exists
        .thenReturn(true)
        .thenReturn(false)
        .thenThrow(new SQLException("Schema does not exist or not authorized", "2000", 2001, null));

    when(mockResultSet.getString("name")).thenReturn("DB1").thenReturn("SCHEMA1");
    when(mockResultSet.getString("database_name")).thenReturn("DB1");

    // Error code 2003
    assertThat(snowflakeClient.schemaExists(SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_2")))
        .isFalse();

    // Error code 2043
    assertThat(snowflakeClient.schemaExists(SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_2")))
        .isFalse();

    // Error code 2001
    assertThat(snowflakeClient.schemaExists(SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_2")))
        .isFalse();
  }

  @Test
  public void testSchemaFailureWithOtherException() throws SQLException {
    Exception injectedException = new SQLException("Some other exception", "2000", 2, null);
    when(mockResultSet.next())
        // The Database exists check should pass, followed by Error code 2 for Schema exists
        .thenReturn(true)
        .thenReturn(false)
        .thenThrow(injectedException);

    when(mockResultSet.getString("name")).thenReturn("DB1").thenReturn("SCHEMA1");
    when(mockResultSet.getString("database_name")).thenReturn("DB1");

    assertThatExceptionOfType(UncheckedSQLException.class)
        .isThrownBy(
            () -> snowflakeClient.schemaExists(SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_2")))
        .withMessageContaining("Failed to check if schema 'SCHEMA: 'DB_1.SCHEMA_2'' exists")
        .withCause(injectedException);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testSchemaFailureWithInterruptedException()
      throws SQLException, InterruptedException {
    Exception injectedException = new InterruptedException("Fake Interrupted exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedInterruptedException.class)
        .isThrownBy(
            () -> snowflakeClient.schemaExists(SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_2")))
        .withMessageContaining("Interrupted while checking if database 'DATABASE: 'DB_1'' exists")
        .withCause(injectedException);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testListDatabasesInAccount() throws SQLException {
    when(mockResultSet.next()).thenReturn(true).thenReturn(true).thenReturn(true).thenReturn(false);
    when(mockResultSet.getString("name")).thenReturn("DB_1").thenReturn("DB_2").thenReturn("DB_3");

    List<SnowflakeIdentifier> actualList = snowflakeClient.listDatabases();

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SHOW DATABASES IN ACCOUNT"),
            any(JdbcSnowflakeClient.ResultSetParser.class));

    assertThat(actualList)
        .containsExactly(
            SnowflakeIdentifier.ofDatabase("DB_1"),
            SnowflakeIdentifier.ofDatabase("DB_2"),
            SnowflakeIdentifier.ofDatabase("DB_3"));
  }

  /**
   * Any unexpected SQLException from the underlying connection will propagate out as a
   * UncheckedSQLException when listing databases at Root level.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListDatabasesSQLExceptionAtRootLevel() throws SQLException, InterruptedException {
    Exception injectedException =
        new SQLException(String.format("SQL exception with Error Code %d", 0), "2000", 0, null);
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedSQLException.class)
        .isThrownBy(() -> snowflakeClient.listDatabases())
        .withMessageContaining("Failed to list databases")
        .withCause(injectedException);
  }

  /**
   * Any unexpected SQLException from the underlying connection will propagate out as an
   * UncheckedSQLException when listing databases if there is no error code.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListDatabasesSQLExceptionWithoutErrorCode()
      throws SQLException, InterruptedException {
    Exception injectedException = new SQLException("Fake SQL exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedSQLException.class)
        .isThrownBy(() -> snowflakeClient.listDatabases())
        .withMessageContaining("Failed to list databases")
        .withCause(injectedException);
  }

  /**
   * Any unexpected InterruptedException from the underlying connection will propagate out as an
   * UncheckedInterruptedException when listing databases.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListDatabasesInterruptedException() throws SQLException, InterruptedException {
    Exception injectedException = new InterruptedException("Fake interrupted exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedInterruptedException.class)
        .isThrownBy(() -> snowflakeClient.listDatabases())
        .withMessageContaining("Interrupted while listing databases")
        .withCause(injectedException);
  }

  /**
   * For the root scope, expect an underlying query to list schemas at the ACCOUNT level with no
   * query parameters.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListSchemasInAccount() throws SQLException {
    when(mockResultSet.next()).thenReturn(true).thenReturn(true).thenReturn(true).thenReturn(false);
    when(mockResultSet.getString("database_name"))
        .thenReturn("DB_1")
        .thenReturn("DB_1")
        .thenReturn("DB_2");
    when(mockResultSet.getString("name"))
        .thenReturn("SCHEMA_1")
        .thenReturn("SCHEMA_2")
        .thenReturn("SCHEMA_3");

    List<SnowflakeIdentifier> actualList =
        snowflakeClient.listSchemas(SnowflakeIdentifier.ofRoot());

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SHOW SCHEMAS IN ACCOUNT"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq(null));

    assertThat(actualList)
        .containsExactly(
            SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_1"),
            SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_2"),
            SnowflakeIdentifier.ofSchema("DB_2", "SCHEMA_3"));
  }

  /**
   * For a DATABASE scope, expect an underlying query to list schemas at the DATABASE level and
   * supply the database as a query param in an IDENTIFIER.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListSchemasInDatabase() throws SQLException {
    when(mockResultSet.next()).thenReturn(true).thenReturn(true).thenReturn(false);
    when(mockResultSet.getString("database_name")).thenReturn("DB_1").thenReturn("DB_1");
    when(mockResultSet.getString("name")).thenReturn("SCHEMA_1").thenReturn("SCHEMA_2");

    List<SnowflakeIdentifier> actualList =
        snowflakeClient.listSchemas(SnowflakeIdentifier.ofDatabase("DB_1"));

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SHOW SCHEMAS IN DATABASE IDENTIFIER(?)"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq("DB_1"));

    assertThat(actualList)
        .containsExactly(
            SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_1"),
            SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_2"));
  }

  /**
   * Any unexpected SQLException from the underlying connection will propagate out as an
   * UncheckedSQLException when listing schemas at Root level.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListSchemasSQLExceptionAtRootLevel() throws SQLException, InterruptedException {
    Exception injectedException =
        new SQLException(String.format("SQL exception with Error Code %d", 0), "2000", 0, null);
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedSQLException.class)
        .isThrownBy(() -> snowflakeClient.listSchemas(SnowflakeIdentifier.ofRoot()))
        .withMessageContaining("Failed to list schemas for scope 'ROOT: '''")
        .withCause(injectedException);
  }

  /**
   * Any unexpected SQLException with specific error codes from the underlying connection will
   * propagate out as a NoSuchNamespaceException when listing schemas at Database level.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListSchemasSQLExceptionAtDatabaseLevel()
      throws SQLException, InterruptedException {
    for (Integer errorCode : DATABASE_NOT_FOUND_ERROR_CODES) {
      Exception injectedException =
          new SQLException(
              String.format("SQL exception with Error Code %d", errorCode),
              "2000",
              errorCode,
              null);
      when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

      assertThatExceptionOfType(NoSuchNamespaceException.class)
          .isThrownBy(() -> snowflakeClient.listSchemas(SnowflakeIdentifier.ofDatabase("DB_1")))
          .withMessageContaining(
              String.format(
                  "Identifier not found: 'DATABASE: 'DB_1''. Underlying exception: 'SQL exception with Error Code %d'",
                  errorCode))
          .withCause(injectedException);
    }
  }

  /** List schemas is not supported at Schema level */
  @Test
  public void testListSchemasAtSchemaLevel() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> snowflakeClient.listSchemas(SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_2")))
        .withMessageContaining("Unsupported scope type for listSchemas: SCHEMA: 'DB_1.SCHEMA_2'");
  }

  /**
   * Any unexpected SQLException from the underlying connection will propagate out as an
   * UncheckedSQLException when listing schemas if there is no error code.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListSchemasSQLExceptionWithoutErrorCode()
      throws SQLException, InterruptedException {
    Exception injectedException = new SQLException("Fake SQL exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedSQLException.class)
        .isThrownBy(() -> snowflakeClient.listSchemas(SnowflakeIdentifier.ofDatabase("DB_1")))
        .withMessageContaining("Failed to list schemas for scope 'DATABASE: 'DB_1''")
        .withCause(injectedException);
  }

  /**
   * Any unexpected InterruptedException from the underlying connection will propagate out as an
   * UncheckedInterruptedException when listing schemas.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListSchemasInterruptedException() throws SQLException, InterruptedException {
    Exception injectedException = new InterruptedException("Fake interrupted exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedInterruptedException.class)
        .isThrownBy(() -> snowflakeClient.listSchemas(SnowflakeIdentifier.ofDatabase("DB_1")))
        .withMessageContaining("Interrupted while listing schemas for scope 'DATABASE: 'DB_1''")
        .withCause(injectedException);
  }

  /**
   * For the root/empty scope, expect an underlying query to list tables at the ACCOUNT level with
   * no query parameters.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListIcebergTablesInAccount() throws SQLException {
    when(mockResultSet.next())
        .thenReturn(true)
        .thenReturn(true)
        .thenReturn(true)
        .thenReturn(true)
        .thenReturn(false);
    when(mockResultSet.getString("database_name"))
        .thenReturn("DB_1")
        .thenReturn("DB_1")
        .thenReturn("DB_1")
        .thenReturn("DB_2");
    when(mockResultSet.getString("schema_name"))
        .thenReturn("SCHEMA_1")
        .thenReturn("SCHEMA_1")
        .thenReturn("SCHEMA_2")
        .thenReturn("SCHEMA_3");
    when(mockResultSet.getString("name"))
        .thenReturn("TABLE_1")
        .thenReturn("TABLE_2")
        .thenReturn("TABLE_3")
        .thenReturn("TABLE_4");

    List<SnowflakeIdentifier> actualList =
        snowflakeClient.listIcebergTables(SnowflakeIdentifier.ofRoot());

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SHOW ICEBERG TABLES IN ACCOUNT"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq(null));

    assertThat(actualList)
        .containsExactly(
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1"),
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_2"),
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_2", "TABLE_3"),
            SnowflakeIdentifier.ofTable("DB_2", "SCHEMA_3", "TABLE_4"));
  }

  /**
   * For a DATABASE scope, expect an underlying query to list tables at the DATABASE level and
   * supply the database as a query param in an IDENTIFIER.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListIcebergTablesInDatabase() throws SQLException {
    when(mockResultSet.next()).thenReturn(true).thenReturn(true).thenReturn(true).thenReturn(false);
    when(mockResultSet.getString("database_name"))
        .thenReturn("DB_1")
        .thenReturn("DB_1")
        .thenReturn("DB_1");
    when(mockResultSet.getString("schema_name"))
        .thenReturn("SCHEMA_1")
        .thenReturn("SCHEMA_1")
        .thenReturn("SCHEMA_2");
    when(mockResultSet.getString("name"))
        .thenReturn("TABLE_1")
        .thenReturn("TABLE_2")
        .thenReturn("TABLE_3");

    List<SnowflakeIdentifier> actualList =
        snowflakeClient.listIcebergTables(SnowflakeIdentifier.ofDatabase("DB_1"));

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SHOW ICEBERG TABLES IN DATABASE IDENTIFIER(?)"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq("DB_1"));

    assertThat(actualList)
        .containsExactly(
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1"),
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_2"),
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_2", "TABLE_3"));
  }

  /**
   * For a SCHEMA scope, expect an underlying query to list tables at the SCHEMA level and supply
   * the schema as a query param in an IDENTIFIER.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListIcebergTablesInSchema() throws SQLException {
    when(mockResultSet.next()).thenReturn(true).thenReturn(true).thenReturn(false);
    when(mockResultSet.getString("database_name")).thenReturn("DB_1").thenReturn("DB_1");
    when(mockResultSet.getString("schema_name")).thenReturn("SCHEMA_1").thenReturn("SCHEMA_1");
    when(mockResultSet.getString("name")).thenReturn("TABLE_1").thenReturn("TABLE_2");

    List<SnowflakeIdentifier> actualList =
        snowflakeClient.listIcebergTables(SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_1"));

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SHOW ICEBERG TABLES IN SCHEMA IDENTIFIER(?)"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq("DB_1.SCHEMA_1"));

    assertThat(actualList)
        .containsExactly(
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1"),
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_2"));
  }

  /**
   * Any unexpected SQLException from the underlying connection will propagate out as an
   * UncheckedSQLException when listing tables at Root level
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListIcebergTablesSQLExceptionAtRootLevel()
      throws SQLException, InterruptedException {
    Exception injectedException =
        new SQLException(String.format("SQL exception with Error Code %d", 0), "2000", 0, null);
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedSQLException.class)
        .isThrownBy(() -> snowflakeClient.listIcebergTables(SnowflakeIdentifier.ofRoot()))
        .withMessageContaining("Failed to list tables for scope 'ROOT: '''")
        .withCause(injectedException);
  }

  /**
   * Any unexpected SQLException with specific error codes from the underlying connection will
   * propagate out as a NoSuchNamespaceException when listing tables at Database level
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListIcebergTablesSQLExceptionAtDatabaseLevel()
      throws SQLException, InterruptedException {
    for (Integer errorCode : DATABASE_NOT_FOUND_ERROR_CODES) {
      Exception injectedException =
          new SQLException(
              String.format("SQL exception with Error Code %d", errorCode),
              "2000",
              errorCode,
              null);
      when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

      assertThatExceptionOfType(NoSuchNamespaceException.class)
          .isThrownBy(
              () -> snowflakeClient.listIcebergTables(SnowflakeIdentifier.ofDatabase("DB_1")))
          .withMessageContaining(
              String.format(
                  "Identifier not found: 'DATABASE: 'DB_1''. Underlying exception: 'SQL exception with Error Code %d'",
                  errorCode))
          .withCause(injectedException);
    }
  }

  /**
   * Any unexpected SQLException with specific error codes from the underlying connection will
   * propagate out as a NoSuchNamespaceException when listing tables at Schema level
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListIcebergTablesSQLExceptionAtSchemaLevel()
      throws SQLException, InterruptedException {
    for (Integer errorCode : SCHEMA_NOT_FOUND_ERROR_CODES) {
      Exception injectedException =
          new SQLException(
              String.format("SQL exception with Error Code %d", errorCode),
              "2000",
              errorCode,
              null);
      when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

      assertThatExceptionOfType(NoSuchNamespaceException.class)
          .isThrownBy(
              () ->
                  snowflakeClient.listIcebergTables(
                      SnowflakeIdentifier.ofSchema("DB_1", "SCHEMA_1")))
          .withMessageContaining(
              String.format(
                  "Identifier not found: 'SCHEMA: 'DB_1.SCHEMA_1''. Underlying exception: 'SQL exception with Error Code %d'",
                  errorCode))
          .withCause(injectedException);
    }
  }

  /**
   * Any unexpected SQLException without error code from the underlying connection will propagate
   * out as an UncheckedSQLException when listing tables.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListIcebergTablesSQLExceptionWithoutErrorCode()
      throws SQLException, InterruptedException {
    Exception injectedException = new SQLException("Fake SQL exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedSQLException.class)
        .isThrownBy(() -> snowflakeClient.listIcebergTables(SnowflakeIdentifier.ofDatabase("DB_1")))
        .withMessageContaining("Failed to list tables for scope 'DATABASE: 'DB_1''")
        .withCause(injectedException);
  }

  /**
   * Any unexpected InterruptedException from the underlying connection will propagate out as an
   * UncheckedInterruptedException when listing tables.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testListIcebergTablesInterruptedException()
      throws SQLException, InterruptedException {
    Exception injectedException = new InterruptedException("Fake interrupted exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedInterruptedException.class)
        .isThrownBy(() -> snowflakeClient.listIcebergTables(SnowflakeIdentifier.ofDatabase("DB_1")))
        .withMessageContaining("Interrupted while listing tables for scope 'DATABASE: 'DB_1''")
        .withCause(injectedException);
  }

  /**
   * Test parsing of table metadata JSON from a GET_ICEBERG_TABLE_INFORMATION call, with the S3 path
   * unaltered between snowflake/iceberg path representations.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testGetS3TableMetadata() throws SQLException {
    when(mockResultSet.next()).thenReturn(true);
    when(mockResultSet.getString("METADATA"))
        .thenReturn(
            "{\"metadataLocation\":\"s3://tab1/metadata/v3.metadata.json\",\"status\":\"success\"}");

    SnowflakeTableMetadata actualMetadata =
        snowflakeClient.loadTableMetadata(
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1"));

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SELECT SYSTEM$GET_ICEBERG_TABLE_INFORMATION(?) AS METADATA"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq("DB_1.SCHEMA_1.TABLE_1"));

    SnowflakeTableMetadata expectedMetadata =
        new SnowflakeTableMetadata(
            "s3://tab1/metadata/v3.metadata.json",
            "s3://tab1/metadata/v3.metadata.json",
            "success",
            null);
    assertThat(actualMetadata).isEqualTo(expectedMetadata);
  }

  /**
   * Test parsing of table metadata JSON from a GET_ICEBERG_TABLE_INFORMATION call, with the Azure
   * path translated from an azure:// format to a wasbs:// format.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testGetAzureTableMetadata() throws SQLException {
    when(mockResultSet.next()).thenReturn(true);
    when(mockResultSet.getString("METADATA"))
        .thenReturn(
            "{\"metadataLocation\":\"azure://myaccount.blob.core.windows.net/mycontainer/tab3/metadata/v334.metadata.json\",\"status\":\"success\"}");

    SnowflakeTableMetadata actualMetadata =
        snowflakeClient.loadTableMetadata(
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1"));

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SELECT SYSTEM$GET_ICEBERG_TABLE_INFORMATION(?) AS METADATA"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq("DB_1.SCHEMA_1.TABLE_1"));

    SnowflakeTableMetadata expectedMetadata =
        new SnowflakeTableMetadata(
            "azure://myaccount.blob.core.windows.net/mycontainer/tab3/metadata/v334.metadata.json",
            "wasbs://mycontainer@myaccount.blob.core.windows.net/tab3/metadata/v334.metadata.json",
            "success",
            null);
    assertThat(actualMetadata).isEqualTo(expectedMetadata);
  }

  /**
   * Test parsing of table metadata JSON from a GET_ICEBERG_TABLE_INFORMATION call, with the GCS
   * path translated from a gcs:// format to a gs:// format.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testGetGcsTableMetadata() throws SQLException {
    when(mockResultSet.next()).thenReturn(true);
    when(mockResultSet.getString("METADATA"))
        .thenReturn(
            "{\"metadataLocation\":\"gcs://tab5/metadata/v793.metadata.json\",\"status\":\"success\"}");

    SnowflakeTableMetadata actualMetadata =
        snowflakeClient.loadTableMetadata(
            SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1"));

    verify(mockQueryHarness)
        .query(
            eq(mockConnection),
            eq("SELECT SYSTEM$GET_ICEBERG_TABLE_INFORMATION(?) AS METADATA"),
            any(JdbcSnowflakeClient.ResultSetParser.class),
            eq("DB_1.SCHEMA_1.TABLE_1"));

    SnowflakeTableMetadata expectedMetadata =
        new SnowflakeTableMetadata(
            "gcs://tab5/metadata/v793.metadata.json",
            "gs://tab5/metadata/v793.metadata.json",
            "success",
            null);
    assertThat(actualMetadata).isEqualTo(expectedMetadata);
  }

  /** Malformed JSON from a ResultSet should propagate as an IllegalArgumentException. */
  @Test
  public void testGetTableMetadataMalformedJson() throws SQLException {
    when(mockResultSet.next()).thenReturn(true);
    when(mockResultSet.getString("METADATA")).thenReturn("{\"malformed_no_closing_bracket");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                snowflakeClient.loadTableMetadata(
                    SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1")))
        .withMessageContaining("{\"malformed_no_closing_bracket");
  }

  /**
   * Any unexpected SQLException with specific error codes from the underlying connection will
   * propagate out as a NoSuchTableException when getting table metadata.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testGetTableMetadataSQLException() throws SQLException, InterruptedException {
    for (Integer errorCode : TABLE_NOT_FOUND_ERROR_CODES) {
      Exception injectedException =
          new SQLException(
              String.format("SQL exception with Error Code %d", errorCode),
              "2000",
              errorCode,
              null);
      when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

      assertThatExceptionOfType(NoSuchTableException.class)
          .isThrownBy(
              () ->
                  snowflakeClient.loadTableMetadata(
                      SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1")))
          .withMessageContaining(
              String.format(
                  "Identifier not found: 'TABLE: 'DB_1.SCHEMA_1.TABLE_1''. Underlying exception: 'SQL exception with Error Code %d'",
                  errorCode))
          .withCause(injectedException);
    }
  }

  /**
   * Any unexpected SQLException from the underlying connection will propagate out as an
   * UncheckedSQLException when getting table metadata.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testGetTableMetadataSQLExceptionWithoutErrorCode()
      throws SQLException, InterruptedException {
    Exception injectedException = new SQLException("Fake SQL exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedSQLException.class)
        .isThrownBy(
            () ->
                snowflakeClient.loadTableMetadata(
                    SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1")))
        .withMessageContaining("Failed to get table metadata for 'TABLE: 'DB_1.SCHEMA_1.TABLE_1''")
        .withCause(injectedException);
  }

  /**
   * Any unexpected InterruptedException from the underlying connection will propagate out as an
   * UncheckedInterruptedException when getting table metadata.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testGetTableMetadataInterruptedException() throws SQLException, InterruptedException {
    Exception injectedException = new InterruptedException("Fake interrupted exception");
    when(mockClientPool.run(any(ClientPool.Action.class))).thenThrow(injectedException);

    assertThatExceptionOfType(UncheckedInterruptedException.class)
        .isThrownBy(
            () ->
                snowflakeClient.loadTableMetadata(
                    SnowflakeIdentifier.ofTable("DB_1", "SCHEMA_1", "TABLE_1")))
        .withMessageContaining(
            "Interrupted while getting table metadata for 'TABLE: 'DB_1.SCHEMA_1.TABLE_1''")
        .withCause(injectedException);
  }

  /** Calling close() propagates to closing underlying client pool. */
  @Test
  public void testClose() {
    snowflakeClient.close();
    verify(mockClientPool).close();
  }
}

package com.dinoparty.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DatabaseProbeTest {

  private final DataSource dataSource = mock(DataSource.class);
  private final DatabaseProbe probe = new DatabaseProbe(dataSource);

  @Test
  void upWhenConnectionIsValid() throws SQLException {
    Connection connection = mock(Connection.class);
    when(connection.isValid(2)).thenReturn(true);
    when(dataSource.getConnection()).thenReturn(connection);

    assertThat(probe.isUp()).isTrue();
  }

  @Test
  void downWhenConnectionCannotBeObtained() throws SQLException {
    when(dataSource.getConnection()).thenThrow(new SQLException("refused"));

    assertThat(probe.isUp()).isFalse();
  }
}

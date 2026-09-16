package com.xnlp.server.evaluation.recovery;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

public class RejectCheckpointTrigger implements Trigger {

    @Override
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("checkpoint rejected for rollback test");
    }
}

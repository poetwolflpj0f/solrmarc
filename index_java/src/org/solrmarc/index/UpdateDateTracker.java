package org.solrmarc.index;

import java.sql.*;
import java.text.SimpleDateFormat;

public class UpdateDateTracker
{
    private Connection db;
    private String core;
    private String id;
    private static SimpleDateFormat iso8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private Timestamp firstIndexed;
    private Timestamp lastIndexed;
    private Timestamp lastRecordChange;
    private Timestamp deleted;

    /* Private support method: create a row in the change_tracker table.
     */
    private void createRow(Timestamp newRecordChange) throws SQLException
    {
        // Save new values to the object:
        java.util.Date rightNow = new java.util.Date();
        firstIndexed = lastIndexed = new Timestamp(rightNow.getTime());
        lastRecordChange = newRecordChange;
        
        // Save new values to the database:
        PreparedStatement sql = db.prepareStatement(
            "INSERT INTO change_tracker(core, id, first_indexed, last_indexed, last_record_change) " +
            "VALUES(?, ?, ?, ?, ?);");
        sql.setString(1, core);
        sql.setString(2, id);
        sql.setTimestamp(3, firstIndexed);
        sql.setTimestamp(4, lastIndexed);
        sql.setTimestamp(5, lastRecordChange);
        sql.executeUpdate();
        sql.close();
    }

    /* Private support method: read a row from the change_tracker table.
     */
    private boolean readRow() throws SQLException
    {
        PreparedStatement sql = db.prepareStatement(
            "SELECT first_indexed, last_indexed, last_record_change, deleted " +
            "FROM change_tracker WHERE core = ? AND id = ?;",
            ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        sql.setString(1, core);
        sql.setString(2, id);
        ResultSet result = sql.executeQuery();
        
        // No results?  Free resources and return false:
        if (!result.first()) {
            result.close();
            sql.close();
            return false;
        }
        
        // If we got this far, we have results -- load them into the object:
        firstIndexed = result.getTimestamp(1);
        lastIndexed = result.getTimestamp(2);
        lastRecordChange = result.getTimestamp(3);
        deleted = result.getTimestamp(4);

        // Free resources and report success:
        result.close();
        sql.close();
        return true;
    }

    /* Private support method: update a row in the change_tracker table.
     */
    private void updateRow(Timestamp newRecordChange) throws SQLException
    {
        // Save new values to the object:
        java.util.Date rightNow = new java.util.Date();
        lastIndexed = new Timestamp(rightNow.getTime());
        // If first indexed is null, we're restoring a deleted record, so
        // we need to treat it as new -- we'll use the current time.
        if (firstIndexed == null) {
            firstIndexed = lastIndexed;
        }
        lastRecordChange = newRecordChange;

        // Save new values to the database:
        PreparedStatement sql = db.prepareStatement("UPDATE change_tracker " +
            "SET first_indexed = ?, last_indexed = ?, last_record_change = ?, deleted = ? " +
            "WHERE core = ? AND id = ?;");
        sql.setTimestamp(1, firstIndexed);
        sql.setTimestamp(2, lastIndexed);
        sql.setTimestamp(3, lastRecordChange);
        sql.setNull(4, java.sql.Types.NULL);
        sql.setString(5, core);
        sql.setString(6, id);
        sql.executeUpdate();
        sql.close();
    }

    /* Constructor:
     */
    public UpdateDateTracker(Connection dbConnection)
    {
        db = dbConnection;
    }

    /* Get the first indexed date (IMPORTANT: index() must be called before this method)
     */
    public String getFirstIndexed()
    {
        return iso8601.format(new java.util.Date(firstIndexed.getTime()));
    }

    /* Get the last indexed date (IMPORTANT: index() must be called before this method)
     */
    public String getLastIndexed()
    {
        return iso8601.format(new java.util.Date(lastIndexed.getTime()));
    }

    /* Update the database to indicate that the record has just been received by the indexer:
     */
    public void index(String selectedCore, String selectedId, java.util.Date recordChange) throws SQLException
    {
        // If core and ID match the values currently in the class, we have already
        // indexed the record and do not need to repeat ourselves!
        if (core == selectedCore && id == selectedId) {
            return;
        }

        // If we made it this far, we need to update the database, so let's store
        // the current core/ID pair we are operating on:
        core = selectedCore;
        id = selectedId;

        // Convert incoming java.util.Date to a Timestamp:
        Timestamp newRecordChange = new Timestamp(recordChange.getTime());

        // No row?  Create one!
        if (!readRow()) {
            createRow(newRecordChange);
        // Row already exists?  See if it needs to be updated:
        } else {
            // Are we restoring a previously deleted record, or was the stored 
            // record change date before current record change date?  Either way,
            // we need to update the table!
            //
            // Note that we check for a time difference of at least one second in
            // order to count as a change.  Because dates are stored with second
            // precision, some of the date conversions have been known to create
            // minor inaccuracies in the millisecond range, which used to cause
            // false positives.
            if (deleted != null || 
                Math.abs(lastRecordChange.getTime() - newRecordChange.getTime()) > 999) {
                updateRow(newRecordChange);
            }
        }
    }
}

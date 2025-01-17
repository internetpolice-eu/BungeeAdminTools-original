package fr.Alphart.BAT.database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import fr.Alphart.BAT.BAT;
import fr.Alphart.BAT.Modules.Core.Core;
import fr.Alphart.BAT.Modules.Core.Importer.Importer.ImportStatus;
import fr.Alphart.BAT.Utils.CallbackUtils.ProgressCallback;
import fr.Alphart.BAT.Utils.Utils;

/**
 * As of version 1.5, BAT now supports fully (case insensitive username) offline mode servers.
 * <p>
 * This class apply all the necessaries operations to <b>migrate to the new database scheme
 * (UNIQUE attribute for BAT_player column)</b> from previous versions.
 */
public class OfflinemodeConverter {

    /**
     * Determine if a migration is required based on database table scheme (searching for
     * the UNIQUE constraint on BAT_player)
     */
    public static boolean isMigrationRequired(final Connection conn) {
        if (Core.isOnlineMode()) {
            return false;
        }

        try {
            DatabaseMetaData dbm = conn.getMetaData();
            try (ResultSet rs = dbm.getIndexInfo(null, null, "BAT_players", false, false)) {
                while (rs.next()) {
                    if (rs.getString("COLUMN_NAME").equalsIgnoreCase("BAT_player") && !rs.getBoolean("NON_UNIQUE")) {
                        return false;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Convert the database to the new scheme
     */
    public static void convertToNewScheme(final ProgressCallback<ImportStatus> progressionCallback, final Connection conn) {
        /*
         * First of all we have to take care of old entries;
         * we've to merge all the punishments of same users with different case and change <b>each</b> UUID
         * to the one generated by Utils#getOfflineUUID which is case unsensitive.
         * 1) Retrieve the entry of the player_name who connected first (minimum firstlogin).
         * 2) Update all this player's punishments UUID to the one generated by Utils#getOfflineUUID
         * which isn't case sensitive
         * 3) Delete all entries of this player of BAT_players except the one we kept.
         * 4) Update the UUID of the entry we kept by the one generated by Utils#getOfflineUUID.
         *
         * Finally we will ALTER the table to add a unique constraint on BAT_player (and delete the old index).
         */

        /*
         * NOTE on the code (the implementation) itself:
         * I (Alphart) really suck at SQL, I've tried to search how to do most of the
         * described procedure in SQL to increase perfomance but i'm really bad at it.
         * Here is an idea of what I would've wanted to do to merge step 1 and 2
         * (it may be completely wrong):
         * -------------------
         * UPDATE BAT_ban
         * SET BAT_ban.UUID = finalUUID
         * JOIN BAT_players ON BAT_ban.UUID = BAT_players.UUID
         * WHERE BAT_players.BAT_player IN
         * (SELECT finalName FROM (SELECT BAT_player as finalName, UUID AS finalUUID, firstlogin FROM bat_players
         *  GROUP BY BAT_player HAVING firstlogin=MIN(firstlogin)) AS subquery);
         *  ------------------
         *  If anyone know how to do this in a better way, feel free to send a PR :)
         */

        ImportStatus importStatus = null;
        Statement finalUUIDstmt = null;
        try {
      /* May be useful in the future, keeping it as comment for now
       * Set correct charset and collation (utf8_general_ci is buggy compared to utf8_unicode_ci)
      BAT.getInstance().getLogger().info("Setting charsets and collation, this might take up to a few minutes.");
      for(String table : Arrays.asList(SQLQueries.Core.table,
          SQLQueries.Ban.table, SQLQueries.Mute.table, SQLQueries.Kick.table, SQLQueries.Comments.table)){
        conn.createStatement().execute("ALTER TABLE " + table + " CONVERT TO CHARACTER SET utf8 COLLATE utf8_unicode_ci;");
      }*/

            BAT.getInstance().getLogger().info("Migration of the data starting. This might take a very long time, please do not quit it once it's launched.");
            finalUUIDstmt = conn.createStatement();
            String finalUUIDstmtString = "SELECT BAT_player as pName, UUID as entryToKeepUUID FROM BAT_players,"
                + " (SELECT BAT_player as foundP, MIN(firstlogin) AS minLogin FROM BAT_players GROUP BY BAT_player) as subquery"
                + " WHERE BAT_player = foundP AND firstlogin = minLogin";
            ResultSet finalUUIDCountResultSet = finalUUIDstmt.executeQuery("SELECT COUNT(*) FROM (" + finalUUIDstmtString + ") as subsubquery;");
            finalUUIDCountResultSet.next();
            int resultsNumber = finalUUIDCountResultSet.getInt("COUNT(*)");

            ResultSet finalUUIDresultSet = finalUUIDstmt.executeQuery(finalUUIDstmtString + ";");
            importStatus = new ImportStatus(resultsNumber > 0 ? resultsNumber : 1); // See constructor avoid exceptions

            conn.setAutoCommit(false);
            while (finalUUIDresultSet.next()) {
                // 1)For each player retrieve their informations
                final String pName = finalUUIDresultSet.getString("pName");
                final String finalUUID = Utils.getOfflineUUID(pName);
                final String entryToKeepUUID = finalUUIDresultSet.getString("entryToKeepUUID");

                try {
                    Map<String, String> UUIDTableFieldToUpdate = new HashMap<>();
                    UUIDTableFieldToUpdate.put("BAT_ban", "UUID");
                    UUIDTableFieldToUpdate.put("BAT_mute", "UUID");
                    UUIDTableFieldToUpdate.put("BAT_kick", "UUID");
                    UUIDTableFieldToUpdate.put("bat_comments", "entity");

                    // 2)For each table we will replace all the different UUIDS of this player with the one we previously determined
                    String updateUUIDStmtTemplate = "UPDATE {table} JOIN BAT_players ON {table}.{UUIDfield} = BAT_players.UUID"
                        + " SET {table}.{UUIDfield} = ?"// ? refers to the finalUUID
                        + " WHERE BAT_players.BAT_player = ?;"; // ? refers to the player name

                    for (Entry<String, String> UUIDTableField : UUIDTableFieldToUpdate.entrySet()) {
                        try (PreparedStatement updateUUIDstmt = conn.prepareStatement(
                            updateUUIDStmtTemplate.replace("{table}", UUIDTableField.getKey()).replace("{UUIDfield}", UUIDTableField.getValue()))) {
                            updateUUIDstmt.setString(1, finalUUID);
                            updateUUIDstmt.setString(2, pName);
                            updateUUIDstmt.executeUpdate();
                        }
                    }

                    // 3)We will delete all the entries from the BAT_players table except the right one
                    String deleteOldEntriesStmtTemplate = "DELETE FROM BAT_players WHERE BAT_player = ? AND UUID != ?;";
                    try (PreparedStatement deleteOldEntriesStmt = conn.prepareStatement(deleteOldEntriesStmtTemplate)) {
                        deleteOldEntriesStmt.setString(1, pName);
                        deleteOldEntriesStmt.setString(2, entryToKeepUUID);
                        deleteOldEntriesStmt.executeUpdate();
                    }

                    // 5)Update the entry with the definitive UUID in BAT_players
                    try (PreparedStatement updateBatPlayersEntryStmt = conn.prepareStatement(
                        "UPDATE BAT_players SET UUID = ? WHERE BAT_player = ?;")) {
                        updateBatPlayersEntryStmt.setString(1, finalUUID);
                        updateBatPlayersEntryStmt.setString(2, pName);
                        updateBatPlayersEntryStmt.executeUpdate();
                    }

                    importStatus.incrementConvertedEntries(1);
                    if (importStatus.getConvertedEntries() % 500 == 0) {
                        conn.commit();
                        progressionCallback.onProgress(importStatus);
                    }
                } catch (SQLException e) {
                    progressionCallback.onMinorError("Error while converting " + pName + "'s data. The process will resume.");
                    DataSourceHandler.handleException(e);
                }
            }

            // Commit all
            conn.commit();
            conn.setAutoCommit(true);

            // Check if there are no more duplicates before creating the UNIQUE index
            ResultSet duplicateCheckRS = conn.createStatement()
                .executeQuery("SELECT COUNT(*) AS duplicates FROM (SELECT BAT_player, COUNT(*) FROM BAT_players GROUP BY BAT_player HAVING COUNT(*) > 1) AS sub;");
            if (duplicateCheckRS.next()) {
                int duplicates = duplicateCheckRS.getInt("duplicates");
                if (duplicates > 0) {
                    String errorMessage = duplicates + " duplicates were found (" + duplicates / importStatus.getTotalEntries() + "% of total entries)."
                        + "To avoid any loss of data, the migration was stopped. Please **BACKUP** your data before and contact the developer (AlphartDev)"
                        + "of the plugin on spigotmc forums.";
                    progressionCallback.done(importStatus, new RuntimeException(errorMessage));
                    return;
                } else {
                    BAT.getInstance().getLogger().info("No duplicates found!");
                }
            }


            // Alter the table so the BAT_player column is UNIQUE
            try (Statement alterTableUniqueStmt = conn.createStatement()) {
                BAT.getInstance().getLogger().info("Altering table structure...");
                alterTableUniqueStmt.executeUpdate("ALTER TABLE BAT_players DROP INDEX `BAT_player`;");
                alterTableUniqueStmt.executeUpdate("ALTER IGNORE TABLE BAT_players ADD UNIQUE (`BAT_player`);");
            }

            // Notify the callback
            importStatus.setDone();
            progressionCallback.done(importStatus, null);
        } catch (SQLException e) {
            DataSourceHandler.handleException(e);

            progressionCallback.done(null, e);
        } finally {
            if (finalUUIDstmt != null) {
                try {
                    finalUUIDstmt.close();
                } catch (SQLException e) {
                    DataSourceHandler.handleException(e);
                }
            }
        }
    }

}

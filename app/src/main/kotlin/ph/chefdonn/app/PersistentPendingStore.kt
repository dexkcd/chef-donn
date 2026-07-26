package ph.chefdonn.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ph.chefdonn.client.PendingStore
import ph.chefdonn.domain.Command
import ph.chefdonn.domain.DomainJson

/**
 * Disk-backed queue: a void tapped during a brownout survives an app kill and a
 * device reboot, then replays on reconnect. Insert order is replay order.
 */
class PersistentPendingStore(context: Context) :
    SQLiteOpenHelper(context, "chefdonn-queue.db", null, 1), PendingStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending (ordinal INTEGER PRIMARY KEY AUTOINCREMENT, command_id TEXT UNIQUE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    override fun loadPending(): List<Command> =
        readableDatabase.rawQuery("SELECT payload FROM pending ORDER BY ordinal ASC", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(DomainJson.decodeFromString(Command.serializer(), c.getString(0)))
                }
            }
        }

    override fun addPending(command: Command) {
        writableDatabase.insertWithOnConflict(
            "pending",
            null,
            ContentValues().apply {
                put("command_id", command.commandId)
                put("payload", DomainJson.encodeToString(Command.serializer(), command))
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    override fun removePending(commandId: String) {
        writableDatabase.delete("pending", "command_id = ?", arrayOf(commandId))
    }

    override fun loadLastSeenSeq(): Long =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'lastSeenSeq'", null).use { c ->
            if (c.moveToFirst()) c.getString(0).toLongOrNull() ?: 0L else 0L
        }

    override fun saveLastSeenSeq(seq: Long) {
        writableDatabase.execSQL(
            "INSERT INTO meta(k, v) VALUES('lastSeenSeq', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            arrayOf(seq.toString()),
        )
    }
}

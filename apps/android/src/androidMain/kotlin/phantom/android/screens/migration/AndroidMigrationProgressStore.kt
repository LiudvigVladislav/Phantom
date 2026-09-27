// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.migration

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import phantom.core.messaging.MigrationProgress
import phantom.core.messaging.MigrationProgressStore

/** No keys or message data; excluded from backup so progress cannot outlive its local database. */
internal class AndroidMigrationProgressStore(private val directory: File) : MigrationProgressStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "alpha2-migration"))

    private val mutex = Mutex()

    private fun file(identityId: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(identityId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, name))
    }

    override suspend fun read(identityId: String): MigrationProgress = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file(identityId)
            val base = file.baseFile
            // An uncommitted first .new file cannot authorize key mutation.
            if (!base.exists() && !File(base.path + ".bak").exists()) {
                return@withLock MigrationProgress.NOT_STARTED
            }
            when (file.readFully().toString(Charsets.UTF_8)) {
                "v1:in-progress\n" -> MigrationProgress.IN_PROGRESS
                "v1:complete\n" -> MigrationProgress.COMPLETE
                else -> error("Migration progress cannot be read safely")
            }
        }
    }

    override suspend fun write(identityId: String, progress: MigrationProgress) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = when (progress) {
                MigrationProgress.IN_PROGRESS -> "v1:in-progress\n"
                MigrationProgress.COMPLETE -> "v1:complete\n"
                MigrationProgress.NOT_STARTED -> error("Migration progress cannot be reset")
            }.toByteArray(Charsets.UTF_8)
            val file = file(identityId)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
            check(file.readFully().contentEquals(bytes)) { "Migration progress write was not confirmed" }
        }
    }
}

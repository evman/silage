package org.siloserver.silo.repository

import org.siloserver.silo.model.download.DownloadSubscription

interface DownloadSubscriptionRepository {
    suspend fun upsert(subscription: DownloadSubscription)
    suspend fun active(serverId: String, profileId: String): List<DownloadSubscription>
    suspend fun all(serverId: String, profileId: String): List<DownloadSubscription>
    suspend fun disable(serverId: String, profileId: String, id: String, updatedAt: Long)
    suspend fun updateEvaluation(
        serverId: String,
        profileId: String,
        id: String,
        evaluatedAt: Long,
        error: String?,
        updatedAt: Long,
    )
    suspend fun deleteAllForServer(serverId: String)
}

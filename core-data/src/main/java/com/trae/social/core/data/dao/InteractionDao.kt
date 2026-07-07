package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import kotlinx.coroutines.flow.Flow

/**
 * 互动数据访问对象。
 *
 * 调度排程：scheduleInteraction 写入 scheduledAt；
 * 调度执行：getPendingBefore 拉取到期互动，markExecuted 标记完成。
 *
 * IMPL-5：insert 使用 IGNORE，配合 (tweetId,accountId,type) 唯一索引实现幂等去重。
 * IMPL-6：markExecuted 与 updateCount 通过 [executeInteractionsAndUpdateTweet] 在
 *         同一 @Transaction 内完成，崩溃后 likeCount/commentCount 不丢失。
 */
@Dao
abstract class InteractionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(interaction: InteractionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(interactions: List<InteractionEntity>): List<Long>

    @Query("SELECT * FROM interactions WHERE scheduledAt <= :time AND executedAt IS NULL ORDER BY scheduledAt ASC")
    abstract suspend fun getPendingBefore(time: Long): List<InteractionEntity>

    @Query("SELECT * FROM interactions WHERE scheduledAt <= :time AND executedAt IS NULL ORDER BY scheduledAt ASC")
    abstract fun observePendingBefore(time: Long): Flow<List<InteractionEntity>>

    @Query("UPDATE interactions SET executedAt = :executedAt WHERE id = :id")
    abstract suspend fun markExecuted(id: String, executedAt: Long)

    @Query("SELECT COUNT(*) FROM interactions WHERE tweetId = :tweetId AND type = :type AND executedAt IS NOT NULL")
    abstract suspend fun countExecutedByType(tweetId: String, type: InteractionType): Int

    @Query("UPDATE tweets SET likeCount = likeCount + :delta WHERE id = :tweetId")
    abstract suspend fun updateTweetLikeCount(tweetId: String, delta: Int)

    @Query("UPDATE tweets SET commentCount = commentCount + :delta WHERE id = :tweetId")
    abstract suspend fun updateTweetCommentCount(tweetId: String, delta: Int)

    @Query("UPDATE tweets SET retweetCount = retweetCount + :delta WHERE id = :tweetId")
    abstract suspend fun updateTweetRetweetCount(tweetId: String, delta: Int)

    /**
     * 原子地标记一批互动已执行并累加对应推文计数（IMPL-6）。
     *
     * 整个操作在同一事务内：任一步骤失败则全部回滚，保证 executedAt 与
     * likeCount/commentCount/retweetCount 的一致性。
     *
     * @param interactions 待执行的互动列表（调用方需预先过滤掉无法执行的项，如无内容的评论）
     * @param executedAt 执行时刻
     * @param tweetId 这些互动关联的推文 ID
     */
    @Transaction
    open suspend fun executeInteractionsAndUpdateTweet(
        interactions: List<InteractionEntity>,
        executedAt: Long,
        tweetId: String,
    ) {
        var likeDelta = 0
        var commentDelta = 0
        var retweetDelta = 0
        for (interaction in interactions) {
            markExecuted(interaction.id, executedAt)
            when (interaction.type) {
                InteractionType.LIKE -> likeDelta++
                InteractionType.COMMENT -> commentDelta++
                InteractionType.RETWEET -> retweetDelta++
                InteractionType.FOLLOW -> { /* FOLLOW 不影响推文计数 */ }
            }
        }
        if (likeDelta > 0) updateTweetLikeCount(tweetId, likeDelta)
        if (commentDelta > 0) updateTweetCommentCount(tweetId, commentDelta)
        if (retweetDelta > 0) updateTweetRetweetCount(tweetId, retweetDelta)
    }
}

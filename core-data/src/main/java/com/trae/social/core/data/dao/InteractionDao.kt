package com.trae.social.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.trae.social.core.data.entity.CommentEntity
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

    // #79：增加 LIMIT 参数，避免单次扫描拉取过多待执行互动导致 Worker 超时
    @Query("SELECT * FROM interactions WHERE scheduledAt <= :now AND executedAt IS NULL ORDER BY scheduledAt ASC LIMIT :limit")
    abstract suspend fun getPendingBefore(now: Long, limit: Int = 50): List<InteractionEntity>

    @Query("SELECT * FROM interactions WHERE scheduledAt <= :time AND executedAt IS NULL ORDER BY scheduledAt ASC")
    abstract fun observePendingBefore(time: Long): Flow<List<InteractionEntity>>

    @Query("UPDATE interactions SET executedAt = :executedAt WHERE id = :id AND executedAt IS NULL")
    abstract suspend fun markExecuted(id: String, executedAt: Long): Int

    @Query("SELECT COUNT(*) FROM interactions WHERE tweetId = :tweetId AND type = :type AND executedAt IS NOT NULL")
    abstract suspend fun countExecutedByType(tweetId: String, type: InteractionType): Int

    // #103：查询某账号已执行的 LIKE 互动关联的推文 ID，替代 likeCount > 0 启发式
    @Query("SELECT tweetId FROM interactions WHERE accountId = :accountId AND type = 'LIKE' AND executedAt IS NOT NULL")
    abstract suspend fun getLikedTweetIdsByAccount(accountId: String): List<String>

    // #166：observe 版本——ProfileViewModel 改用 Flow 订阅，FeedViewModel 点赞后 Profile LIKES Tab 自动刷新
    @Query("SELECT tweetId FROM interactions WHERE accountId = :accountId AND type = 'LIKE' AND executedAt IS NOT NULL")
    abstract fun observeLikedTweetIdsByAccount(accountId: String): Flow<List<String>>

    // M7 修复：删除某账号对某推文的 LIKE 互动记录（取消点赞时调用）
    @Query("DELETE FROM interactions WHERE tweetId = :tweetId AND accountId = :accountId AND type = 'LIKE'")
    abstract suspend fun deleteLikeInteraction(tweetId: String, accountId: String)

    /**
     * #316：判断某账号对某推文是否存在已执行的 LIKE 记录。
     *
     * 单条 EXISTS 查询替代 `getLikedTweetIdsByAccount(...).contains(tweetId)` 的全量加载
     * 模式——后者加载该账号全部已点赞推文 ID 列表只为检查单个 ID 是否存在，N 条 LIKE
     * 即拉取 N 行。EXISTS 在找到首条匹配行后即短路返回，开销恒定为 O(log N)。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM interactions WHERE tweetId = :tweetId AND accountId = :accountId AND type = 'LIKE' AND executedAt IS NOT NULL)")
    abstract suspend fun hasLikeInteraction(tweetId: String, accountId: String): Boolean

    // m7 修复：删除某账号对某推文的 COMMENT 互动记录（评论失败回滚时清理孤儿 interaction）
    @Query("DELETE FROM interactions WHERE tweetId = :tweetId AND accountId = :accountId AND type = 'COMMENT'")
    abstract suspend fun deleteCommentInteraction(tweetId: String, accountId: String)

    @Query("UPDATE tweets SET likeCount = likeCount + :delta WHERE id = :tweetId")
    abstract suspend fun updateTweetLikeCount(tweetId: String, delta: Int)

    @Query("UPDATE tweets SET commentCount = commentCount + :delta WHERE id = :tweetId")
    abstract suspend fun updateTweetCommentCount(tweetId: String, delta: Int)

    @Query("UPDATE tweets SET retweetCount = retweetCount + :delta WHERE id = :tweetId")
    abstract suspend fun updateTweetRetweetCount(tweetId: String, delta: Int)

    // #99：AI 评论执行时同步写入 comments 表，使评论弹层能展示 AI 评论
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertComment(comment: CommentEntity)

    /**
     * 原子地标记一批互动已执行并累加对应推文计数（IMPL-6）。
     *
     * 整个操作在同一事务内：任一步骤失败则全部回滚，保证 executedAt 与
     * likeCount/commentCount/retweetCount 的一致性。
     *
     * 幂等守卫：[markExecuted] 仅在 `executedAt IS NULL` 时更新（返回受影响行数），
     * 已执行的互动不会重复计数，避免 Worker 重试导致 likeCount 翻倍。
     *
     * #115：返回实际执行（rowsAffected > 0）的互动数，供调用方按真实执行数累加 processed，
     * 避免并发重复扫描时 processed 按批大小虚高。
     *
     * @param interactions 待执行的互动列表（调用方需预先过滤掉无法执行的项，如无内容的评论）
     * @param executedAt 执行时刻
     * @param tweetId 这些互动关联的推文 ID
     * @return 实际执行（未被幂等守卫跳过）的互动数
     */
    @Transaction
    open suspend fun executeInteractionsAndUpdateTweet(
        interactions: List<InteractionEntity>,
        executedAt: Long,
        tweetId: String,
    ): Int {
        var likeDelta = 0
        var commentDelta = 0
        var retweetDelta = 0
        var executedCount = 0
        for (interaction in interactions) {
            val rowsAffected = markExecuted(interaction.id, executedAt)
            // 已执行的互动（rowsAffected == 0）跳过计数，防重复累加
            if (rowsAffected == 0) continue
            executedCount++
            when (interaction.type) {
                InteractionType.LIKE -> likeDelta++
                InteractionType.COMMENT -> {
                    commentDelta++
                    // #99：AI 评论执行时同步写入 comments 表，
                    // 使评论弹层能展示 AI 生成的评论（此前仅存于 interactions.content）
                    if (!interaction.content.isNullOrBlank()) {
                        insertComment(
                            CommentEntity(
                                id = interaction.id,
                                tweetId = tweetId,
                                authorId = interaction.accountId,
                                content = interaction.content,
                                createdAt = executedAt,
                            )
                        )
                    }
                }
                InteractionType.RETWEET -> retweetDelta++
                InteractionType.FOLLOW -> { /* FOLLOW 不影响推文计数 */ }
            }
        }
        if (likeDelta > 0) updateTweetLikeCount(tweetId, likeDelta)
        if (commentDelta > 0) updateTweetCommentCount(tweetId, commentDelta)
        if (retweetDelta > 0) updateTweetRetweetCount(tweetId, retweetDelta)
        return executedCount
    }
}

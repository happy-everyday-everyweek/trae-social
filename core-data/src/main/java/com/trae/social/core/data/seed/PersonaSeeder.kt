package com.trae.social.core.data.seed

import android.content.Context
import androidx.room.withTransaction
import com.trae.social.core.data.dao.AccountDao
import com.trae.social.core.data.dao.TweetDao
import com.trae.social.core.data.db.AppDatabase
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.TweetEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import timber.log.Timber

/**
 * 人设种子数据加载器。
 *
 * - 首次启动读取 assets/personas/personas_*.json（10 分片，每片 20+ 条）
 * - 异步执行（Dispatchers.IO），不阻塞 UI
 * - 提供 [Flow]<[SeedProgress]> 反馈导入进度
 * - 幂等：accounts 表非空时直接跳过
 * - 同时导入人设自带历史推文（RISK-14：营造账号早已存在）
 * - 当 assets/personas/ 不存在（Task 7 尚未生成）时优雅返回
 * - IMPL-1/IMPL-3/IMPL-12：插入 id="user-self" 的真实账号，使 InteractionWorker 不短路、
 *   信息流 resolveAuthor 能显示用户资料
 * - IMPL-24：每文件的 accounts + tweets 写入在同一事务内，崩溃后数据完整
 */
@Singleton
class PersonaSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val accountDao: AccountDao,
    private val tweetDao: TweetDao
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val isSeeding = AtomicBoolean(false)

    /**
     * 触发种子导入（冷流，收集时执行）。
     */
    fun seedIfNeeded(): Flow<SeedProgress> = flow {
        if (!isSeeding.compareAndSet(false, true)) {
            emit(SeedProgress(imported = 0, total = SeedProgress.EXPECTED_TOTAL, isComplete = true))
            return@flow
        }
        try {
            // 幂等：accounts 表非空则跳过
            val existing = accountDao.count()
            if (existing > 0) {
                emit(SeedProgress(imported = existing, total = SeedProgress.EXPECTED_TOTAL, isComplete = true))
                return@flow
            }

            // IMPL-1/IMPL-3/IMPL-12：先插入 user-self 账号，确保 InteractionWorker 不短路
            ensureUserSelfAccount()

            val allFiles = listPersonaFiles()
            if (allFiles.isNullOrEmpty()) {
                emit(
                    SeedProgress(
                        imported = 1,
                        total = SeedProgress.EXPECTED_TOTAL,
                        isComplete = true,
                        errorMessage = "assets/personas/ 目录不存在或为空，仅初始化 user-self 账号"
                    )
                )
                return@flow
            }

            val personaFiles = allFiles
                .filter { it.startsWith(PERSONAS_FILE_PREFIX) && it.endsWith(".json") }
                .sorted()

            if (personaFiles.isEmpty()) {
                emit(
                    SeedProgress(
                        imported = 1,
                        total = SeedProgress.EXPECTED_TOTAL,
                        isComplete = true,
                        errorMessage = "未找到 personas_*.json 文件，仅初始化 user-self 账号"
                    )
                )
                return@flow
            }

            val now = System.currentTimeMillis()
            var imported = 1 // user-self 已计入

            for (fileName in personaFiles) {
                val personas = runCatching { parsePersonaFile(fileName) }
                    .getOrElse { e ->
                        Timber.w(e, "解析人设文件失败: $fileName")
                        emptyList()
                    }
                if (personas.isEmpty()) continue

                val accounts = personas.map { it.toAccountEntity(now) }
                val tweets = personas.flatMap { dto -> dto.toHistoricalTweetEntities(now) }

                // IMPL-24：accounts + tweets 在同一事务内写入，保证原子性
                database.withTransaction {
                    accountDao.upsertAll(accounts)
                    if (tweets.isNotEmpty()) {
                        tweetDao.insertAll(tweets)
                    }
                }

                imported += personas.size
                emit(SeedProgress(imported = imported, total = SeedProgress.EXPECTED_TOTAL, isComplete = false))
            }

            emit(SeedProgress(imported = imported, total = SeedProgress.EXPECTED_TOTAL, isComplete = true))
        } finally {
            isSeeding.set(false)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 插入 user-self 账号（用户自己的账号）。
     *
     * id 固定为 [USER_SELF_ID]，与 PublishViewModel.AUTHOR_SELF 一致。
     * isVirtual=false，不参与 AI 调度。
     */
    private suspend fun ensureUserSelfAccount() {
        if (accountDao.getById(USER_SELF_ID) != null) return
        val now = System.currentTimeMillis()
        val userSelf = AccountEntity(
            id = USER_SELF_ID,
            displayName = "我",
            username = "user",
            avatarSeed = USER_SELF_ID,
            bio = "",
            profession = "",
            ageRange = "",
            culturalBackground = "",
            worldview = "",
            values = "",
            languageStyle = "",
            catchphrase = emptyList(),
            emojiPreference = emptyList(),
            typoRate = 0.0,
            activeWindows = List(ACTIVE_WINDOW_SIZE) { false },
            isVirtual = false,
            createdAt = now,
            updatedAt = now,
            dynamicLifeStory = "",
            dynamicWorkInfo = "",
            recentMood = ""
        )
        accountDao.upsert(userSelf)
    }

    private fun listPersonaFiles(): Array<String>? =
        runCatching { context.assets.list(PERSONAS_DIR) }.getOrNull()

    private fun parsePersonaFile(fileName: String): List<PersonaDto> {
        val text = context.assets.open("$PERSONAS_DIR/$fileName").bufferedReader().use { it.readText() }
        return json.decodeFromString(ListSerializer(PersonaDto.serializer()), text)
    }

    private fun PersonaDto.toAccountEntity(now: Long): AccountEntity = AccountEntity(
        id = id,
        displayName = displayName,
        username = username,
        avatarSeed = avatarSeed,
        bio = bio,
        profession = profession,
        ageRange = ageRange,
        culturalBackground = culturalBackground,
        worldview = worldview,
        values = values,
        languageStyle = languageStyle,
        catchphrase = catchphrase,
        emojiPreference = emojiPreference,
        typoRate = typoRate,
        activeWindows = normalizeActiveWindows(activeWindows),
        isVirtual = true,
        createdAt = now,
        updatedAt = now,
        dynamicLifeStory = "",
        dynamicWorkInfo = profession,
        recentMood = ""
    )

    private fun PersonaDto.toHistoricalTweetEntities(now: Long): List<TweetEntity> {
        return historicalTweets.mapIndexed { index, tweet ->
            val daysAgo = tweet.daysAgo.coerceIn(1, 30)
            val withinDayOffset = Random.nextLong(0, DAY_MS)
            // IMPL-9：减号确保 tweetTime 落入 [now-(daysAgo+1)*DAY_MS, now-daysAgo*DAY_MS]
            val tweetTime = now - daysAgo * DAY_MS - withinDayOffset
            TweetEntity(
                id = UUID.randomUUID().toString(),
                authorId = id,
                text = tweet.text,
                mediaPath = null,
                mediaTheme = tweet.mediaTheme,
                createdAt = tweetTime,
                likeCount = Random.nextInt(0, 80),
                commentCount = Random.nextInt(0, 15),
                retweetCount = Random.nextInt(0, 25),
                isAiGenerated = true,
                deduplicationKey = "seed_${id}_$index"
            )
        }
    }

    private fun normalizeActiveWindows(windows: List<Boolean>): List<Boolean> {
        val result = windows.take(ACTIVE_WINDOW_SIZE).toMutableList()
        while (result.size < ACTIVE_WINDOW_SIZE) {
            result.add(false)
        }
        return result.toList()
    }

    companion object {
        private const val PERSONAS_DIR = "personas"
        private const val PERSONAS_FILE_PREFIX = "personas_"
        private const val ACTIVE_WINDOW_SIZE = 24
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        const val USER_SELF_ID = "user-self"
    }
}

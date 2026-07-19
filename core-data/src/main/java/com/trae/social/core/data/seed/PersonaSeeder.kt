package com.trae.social.core.data.seed

import android.content.Context
import androidx.room.withTransaction
import com.trae.social.core.data.AccountIds
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import timber.log.Timber

/**
 * 人设种子数据加载器。
 *
 * - 首次启动读取 assets/personas/personas_*.json（11 分片，每片 20 条，共 220 条）
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
            // 幂等：accounts 已达预期且 tweets 非空时跳过（崩溃中断后 tweets 会缺失，需补导）
            val existingAccounts = accountDao.count()
            val existingTweets = tweetDao.count()
            if (existingAccounts >= SeedProgress.EXPECTED_TOTAL && existingTweets > 0) {
                // m2 修复：幂等跳过路径也执行 index.json 校验，确保 count 与实际导入数一致
                validateIndexJson((existingAccounts - 1).coerceAtLeast(0))
                emit(SeedProgress(imported = existingAccounts, total = SeedProgress.EXPECTED_TOTAL, isComplete = true))
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
            // P2 修复：imported 只统计虚拟账号（排除 user-self），避免 imported(221) > total(220) 进度超 100%
            var imported = (accountDao.count() - 1).coerceAtLeast(0) // 已有虚拟账号数（减去 user-self）

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
                // IMPL-38：使用 upsertAllWithActiveHours 同步活跃小时反向索引
                // 幂等重导：accounts 用 @Upsert（不触发 CASCADE），tweets 用 insertAllOrIgnore（跳过已存在）
                database.withTransaction {
                    accountDao.upsertAllWithActiveHours(accounts)
                    if (tweets.isNotEmpty()) {
                        tweetDao.insertAllOrIgnore(tweets)
                    }
                }

                imported += personas.size
                emit(SeedProgress(imported = imported, total = SeedProgress.EXPECTED_TOTAL, isComplete = false))
            }

            emit(SeedProgress(imported = imported, total = SeedProgress.EXPECTED_TOTAL, isComplete = true))

            // #97：校验 index.json 的 count 与实际导入的账号数是否一致
            validateIndexJson(imported)
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
            avatarSeed = "user",  // #92：avatarSeed 与 username 保持一致
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
        // IMPL-38：user-self 无活跃窗口（isVirtual=false），但仍走同步方法保持索引一致
        accountDao.upsertWithActiveHours(userSelf)
    }

    private fun listPersonaFiles(): Array<String>? =
        runCatching { context.assets.list(PERSONAS_DIR) }.getOrNull()

    /**
     * #97：校验 index.json 的 count 字段与实际导入的账号数是否一致。
     *
     * index.json 由 generate.py 生成，若重新生成人设后忘记更新，count 会与实际数据不一致。
     * 此方法读取 index.json 并比对，不一致时输出警告日志。
     */
    private fun validateIndexJson(actualCount: Int) {
        runCatching {
            val text = context.assets.open("$PERSONAS_DIR/index.json").bufferedReader().use { it.readText() }
            val indexJson = json.parseToJsonElement(text).jsonObject
            val expectedCount = indexJson["count"]?.jsonPrimitive?.intOrNull
            if (expectedCount != null && expectedCount != actualCount) {
                Timber.w("index.json count=%d 与实际导入账号数=%d 不一致，请重新运行 generate.py 更新 index.json", expectedCount, actualCount)
            }
        }.onFailure { Timber.w(it, "读取 index.json 失败，跳过校验") }
    }

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
                mediaTheme = null,  // #76：mediaPath 为 null 时 mediaTheme 也置空，保持数据不变式一致
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
        // #220：USER_SELF_ID 已抽到 AccountIds.USER_SELF_ID，此处保留别名仅向后兼容
        // 已有外部引用（如 EventTextPreParser.userSelfId），新代码应直接引用 AccountIds
        const val USER_SELF_ID = AccountIds.USER_SELF_ID
    }
}

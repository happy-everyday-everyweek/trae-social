package com.trae.social.core.data.gallery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import com.trae.social.core.data.gallery.di.GalleryJson
import com.trae.social.core.data.util.runCatchingCancellable

/**
 * [LocalImageGallery] 的默认实现。
 *
 * 工作流程：
 * 1. 首次调用时通过 [AssetProvider] 读取 assets/gallery/index.json，
 *    在内存中缓存 主题 -> 文件名列表 映射。
 * 2. 选取时查询 [ImageUsagePort] 该账号 30 天内已用图片并排除。
 * 3. 指定主题图片耗尽时，按相邻主题回退选取。
 * 4. 选取成功后调用 [ImageUsagePort.recordUsage] 记录本次使用。
 *
 * 线程安全：index 加载使用 [Mutex] 保证只加载一次；选取流程为无状态读取。
 */
@Singleton
class LocalImageGalleryImpl @Inject constructor(
    private val assetProvider: AssetProvider,
    private val imageUsage: ImageUsagePort,
    @GalleryJson private val json: Json,
) : LocalImageGallery {

    /** assets 中图库根目录。 */
    private val galleryRoot: String = "gallery"

    /** index.json 相对路径。 */
    private val indexPath: String = "$galleryRoot/index.json"

    /** 去重窗口：30 天。 */
    private val dedupWindowMillis: Long = TimeUnit.DAYS.toMillis(30)

    /** 内存缓存：主题 -> 文件名列表。 */
    @Volatile
    private var indexCache: Map<String, List<String>>? = null

    private val indexMutex = Mutex()

    override suspend fun pickRandom(theme: String, accountId: String): String? =
        withContext(Dispatchers.Default) {
            val index = ensureIndexLoaded()
            if (index.isEmpty()) {
                Timber.w("gallery index empty, cannot pick image")
                return@withContext null
            }

            val sinceMillis = nowMillis() - dedupWindowMillis
            val used: Set<String> = runCatchingCancellable {
                imageUsage.recentlyUsedAssets(accountId, sinceMillis)
            }.getOrElse {
                Timber.w(it, "query recently used assets failed, fallback to empty set")
                emptySet()
            }

            val candidates = candidateThemes(theme, index)
            for (currentTheme in candidates) {
                val files = index[currentTheme].orEmpty()
                if (files.isEmpty()) continue
                val available = files.filter { name ->
                    val path = "$galleryRoot/$currentTheme/$name"
                    path !in used
                }
                if (available.isEmpty()) continue
                val picked = available.random(Random.Default)
                val assetPath = "$galleryRoot/$currentTheme/$picked"
                runCatchingCancellable {
                    imageUsage.recordUsage(accountId, assetPath, nowMillis())
                }.onFailure {
                    Timber.w(it, "record image usage failed (will still return picked path)")
                }
                return@withContext assetPath
            }

            Timber.w("no available gallery image for theme=%s account=%s", theme, accountId)
            null
        }

    /**
     * 计算候选主题顺序：指定主题优先；其后按相邻主题回退。
     *
     * 当 theme 为空或未在索引中时，按索引中所有主题随机排列回退。
     */
    private fun candidateThemes(theme: String, index: Map<String, List<String>>): List<String> {
        val all = index.keys.toList()
        if (theme.isBlank() || theme !in index) {
            return all.shuffled(Random.Default)
        }
        if (all.size <= 1) return listOf(theme)
        // 将 all 中 theme 置顶，其余按原顺序作为回退
        val others = all.filter { it != theme }
        return listOf(theme) + others
    }

    /** 懒加载 index.json，仅解析一次。 */
    private suspend fun ensureIndexLoaded(): Map<String, List<String>> {
        indexCache?.let { return it }
        indexMutex.withLock {
            indexCache?.let { return it }
            val loaded = loadIndex()
            indexCache = loaded
            return loaded
        }
    }

    private suspend fun loadIndex(): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                assetProvider.openAsset(indexPath).use { stream ->
                    val text = stream.bufferedReader(Charsets.UTF_8).readText()
                    parseIndex(text)
                }
            }.getOrElse {
                Timber.e(it, "load gallery index failed: %s", indexPath)
                emptyMap()
            }
        }

    /**
     * 解析 index.json 文本为 主题 -> 文件名列表。
     * 容错：单条解析失败时跳过该项，不影响其他主题。
     */
    private fun parseIndex(text: String): Map<String, List<String>> {
        val element = json.parseToJsonElement(text)
        if (element !is JsonObject) return emptyMap()
        val result = LinkedHashMap<String, List<String>>()
        for ((key, value) in element) {
            if (value !is JsonArray) continue
            val files = value.mapNotNull { item ->
                runCatching { item.jsonPrimitive.content }.getOrNull()
            }
            result[key] = files
        }
        return result
    }

    /** 可替换的时间源，便于测试。 */
    private fun nowMillis(): Long = System.currentTimeMillis()
}

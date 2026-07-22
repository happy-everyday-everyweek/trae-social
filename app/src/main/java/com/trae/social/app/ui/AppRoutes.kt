package com.trae.social.app.ui

import android.net.Uri
import com.trae.social.profile.FollowListType

/**
 * 应用导航路由常量集中定义（P2 风格一致性修复）。
 *
 * 避免在 [com.trae.social.app.MainActivity] 与 [SocialBottomBar] 等多处硬编码路由字符串，
 * 防止拼写不一致导致的导航失败。
 */
object AppRoutes {
    /** 顶层路由：引导流程 */
    const val ONBOARDING = "onboarding"

    /** 顶层路由：主框架（底部 Tab 容器） */
    const val MAIN = "main"

    /** 主 Tab：首页 Feed */
    const val FEED = "feed"

    /** 主 Tab：时间线 */
    const val TIMELINE = "timeline"

    /** 主 Tab：我的 */
    const val PROFILE = "profile"

    /** 全屏路由：设置页 */
    const val SETTINGS = "settings"

    /** 全屏路由：API Key 管理 */
    const val API_KEY = "apikey"

    /** 全屏路由：开发者选项 */
    const val DEV_OPTIONS = "devoptions"

    /** #146：全屏路由：画像对话页（用户反馈智能体） */
    const val OPEN_PROFILE_CHAT = "profile_chat"

    /** 全屏路由：发布 */
    const val PUBLISH = "publish"

    /** 全屏路由：关注/粉丝列表（带参数 {type}） */
    const val FOLLOW_LIST = "followlist/{type}"

    /** 关注列表参数键 */
    const val FOLLOW_LIST_TYPE_ARG = "type"

    /** #11：全屏路由：账号详情页（带参数 {accountId}） */
    const val ACCOUNT_DETAIL = "account/{accountId}"

    /** #11：账号详情参数键 */
    const val ACCOUNT_DETAIL_ID_ARG = "accountId"

    /**
     * 构造关注列表路由（[FOLLOW_LIST_TYPE_ARG] 为枚举名称）。
     *
     * #213：对 [type] 做 [Uri.encode] 后再拼装，避免未来枚举值含 `/ ? & #` 等
     * 保留字符导致路由匹配失败或参数被截断；读取侧用 [decodeFollowListType] 反解。
     */
    fun followList(type: FollowListType): String =
        "followlist/${Uri.encode(type.name)}"

    /**
     * #11：构造账号详情路由。
     *
     * accountId 来自 [com.trae.social.core.data.entity.AccountEntity.id]，
     * 为 UUID 字符串（含 `-`），无需 URL 编码即可作为路径段。
     */
    fun accountDetail(accountId: String): String = "account/$accountId"

    /**
     * 从路由参数还原 [FollowListType]，与 [followList] 配套使用。
     *
     * 字符串路由模式下 [NavType.StringType.parseValue] 直接返回原始捕获值
     * （不会自动 URL 解码），需在此处 [Uri.decode] 还原 [followList] 中的
     * [Uri.encode]。用 [runCatching] 兜底，避免含 `%` 但非合法 `%XX` 转义的
     * 字符串在 [Uri.decode] 中抛出。
     *
     * 非法值降级为 [FollowListType.FOLLOWING]。
     */
    fun decodeFollowListType(raw: String?): FollowListType {
        val name = raw?.let { runCatching { Uri.decode(it) }.getOrDefault(it) }
        return runCatching { FollowListType.valueOf(name ?: "") }
            .getOrElse { FollowListType.FOLLOWING }
    }
}

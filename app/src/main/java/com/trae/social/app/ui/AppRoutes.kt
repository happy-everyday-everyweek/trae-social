package com.trae.social.app.ui

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

    /** 全屏路由：发布 */
    const val PUBLISH = "publish"

    /** 全屏路由：关注/粉丝列表（带参数 {type}） */
    const val FOLLOW_LIST = "followlist/{type}"

    /** 关注列表参数键 */
    const val FOLLOW_LIST_TYPE_ARG = "type"

    /**
     * 构造关注列表路由（[FOLLOW_LIST_TYPE_ARG] 为枚举名称）。
     */
    fun followList(type: String): String = "followlist/$type"
}

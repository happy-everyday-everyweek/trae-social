package com.trae.social.data.gallery

/**
 * 配图主题枚举。
 *
 * 与 assets/gallery/<theme>/ 目录一一对应。
 * [NONE] 表示不指定主题，选取时由调用方决定回退策略。
 */
enum class GalleryImageTheme {
    LANDSCAPE,
    FOOD,
    CITY,
    PET,
    SPORT,
    ART,
    TECH,
    NATURE,
    NONE;

    companion object {
        /**
         * 将字符串解析为 [GalleryImageTheme]，无法识别时返回 [NONE]。
         * 解析大小写不敏感。
         */
        fun fromString(value: String?): GalleryImageTheme {
            if (value.isNullOrBlank()) return NONE
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NONE
        }
    }
}

/**
 * 将 [GalleryImageTheme] 转换为 assets 目录名。
 * [GalleryImageTheme.NONE] 返回空字符串，调用方需自行处理。
 */
fun themeToString(theme: GalleryImageTheme): String {
    return when (theme) {
        GalleryImageTheme.NONE -> ""
        else -> theme.name.lowercase()
    }
}

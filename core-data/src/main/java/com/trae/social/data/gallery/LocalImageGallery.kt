package com.trae.social.data.gallery

/**
 * 本地配图库查询接口。
 *
 * 虚拟账号发布带图推文时，通过该接口从内置 assets 图库中按主题随机选取
 * 一张配图，并对同一账号 30 天内已用图片做去重。
 *
 * 返回的路径为相对 assets 根的路径，例如 "gallery/landscape/3.svg"。
 * 调用方可直接通过 [AssetProvider.openAsset] 读取，或交给 Coil 等图片库加载。
 */
interface LocalImageGallery {

    /**
     * 为指定账号随机选取一张主题配图。
     *
     * @param theme 主题目录名，例如 "landscape" / "food"。可传入 [GalleryImageTheme]
     *     经 [themeToString] 转换得到；为空时按整体图库随机选取。
     * @param accountId 虚拟账号 ID，用于去重。
     * @return asset 路径（如 "gallery/landscape/3.svg"），无可用图时返回 null。
     */
    suspend fun pickRandom(theme: String, accountId: String): String?
}

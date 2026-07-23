package com.trae.social.core.data.gallery

import java.io.InputStream

/**
 * 资源（assets）访问抽象。
 *
 * 由于 assets 实际存放于 app 模块，core-data 无法直接访问 AssetManager。
 * 通过该接口将 assets 读取能力抽象出来，由 app 模块提供实现并注入。
 *
 * 路径约定：使用相对 assets 根目录的路径，例如 "gallery/index.json"
 * 或 "gallery/landscape/3.svg"，与 Android AssetManager.list / open 接口一致。
 */
interface AssetProvider {

    /**
     * 列出指定 assets 目录下的文件名（不含子目录）。
     * 若路径不存在返回空列表。
     */
    fun listAssets(path: String): List<String>

    /**
     * 打开指定 assets 文件，返回输入流。调用方负责关闭。
     */
    fun openAsset(path: String): InputStream
}

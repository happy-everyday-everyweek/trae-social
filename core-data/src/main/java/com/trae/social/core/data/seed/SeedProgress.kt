package com.trae.social.core.data.seed

/**
 * 种子导入进度（UI 展示用）。
 *
 * @param imported 已导入账号数
 * @param total 预估总数（220）
 * @param isComplete 是否完成
 * @param errorMessage 错误信息（文件缺失等）
 */
data class SeedProgress(
    val imported: Int,
    val total: Int,
    val isComplete: Boolean,
    val errorMessage: String? = null
) {
    companion object {
        const val EXPECTED_TOTAL = 220
    }
}

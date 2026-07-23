package com.trae.social.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DatabaseMigrations] / [ALL_MIGRATIONS] 单元测试（#282）。
 *
 * 验证迁移数组结构完整性：数量、版本连续性。
 * 实际的 SQL 迁移执行测试（MigrationTestHelper）需 Android instrumentation 环境，
 * 待 #295 在有 Android SDK 的环境中补全 schema JSON 后可接入。
 */
class DatabaseMigrationsTest {

    @Test
    fun `ALL_MIGRATIONS 包含 7 个迁移`() {
        assertEquals(7, ALL_MIGRATIONS.size)
    }

    @Test
    fun `迁移版本从 1 连续递增到 8`() {
        val ranges = ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(
            listOf(
                1 to 2, // IMPL-5: interactions 唯一索引
                2 to 3, // IMPL-16: accounts.timezone
                3 to 4, // IMPL-22: 外键级联
                4 to 5, // IMPL-38: account_active_hours 表
                5 to 6, // comments 表
                6 to 7, // #146: 用户行为建模六张表
                7 to 8, // #227/#151: 索引清理 + llm_endpoints 表
            ),
            ranges,
        )
    }

    @Test
    fun `每个迁移的 endVersion 等于下一个迁移的 startVersion`() {
        for (i in 0 until ALL_MIGRATIONS.size - 1) {
            assertEquals(
                "迁移 ${i} 与 ${i + 1} 之间版本不连续",
                ALL_MIGRATIONS[i].endVersion,
                ALL_MIGRATIONS[i + 1].startVersion,
            )
        }
    }

    @Test
    fun `首个迁移从 version 1 开始，末个迁移到 version 8 结束`() {
        assertEquals(1, ALL_MIGRATIONS.first().startVersion)
        assertEquals(8, ALL_MIGRATIONS.last().endVersion)
    }
}

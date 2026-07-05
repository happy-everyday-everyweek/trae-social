package com.trae.social.core.scheduler.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [DeduplicationKeys.forTweet] 单元测试。
 *
 * 覆盖：键格式、相同输入相同输出、不同输入不同输出、参数校验。
 */
class DeduplicationKeysTest {

    @Test
    fun `相同输入生成相同去重键`() {
        val key1 = DeduplicationKeys.forTweet("acc1", 1_000L, 0)
        val key2 = DeduplicationKeys.forTweet("acc1", 1_000L, 0)
        assertEquals(key1, key2)
    }

    @Test
    fun `去重键格式为 accountId_windowStart_sequenceNo`() {
        val key = DeduplicationKeys.forTweet("acc123", 1_700_000_000_000L, 2)
        assertEquals("acc123_1700000000000_2", key)
    }

    @Test
    fun `不同账号生成不同去重键`() {
        val key1 = DeduplicationKeys.forTweet("acc1", 1_000L, 0)
        val key2 = DeduplicationKeys.forTweet("acc2", 1_000L, 0)
        assertNotEquals(key1, key2)
    }

    @Test
    fun `不同 windowStart 生成不同去重键`() {
        val key1 = DeduplicationKeys.forTweet("acc1", 1_000L, 0)
        val key2 = DeduplicationKeys.forTweet("acc1", 2_000L, 0)
        assertNotEquals(key1, key2)
    }

    @Test
    fun `不同 sequenceNo 生成不同去重键`() {
        val key1 = DeduplicationKeys.forTweet("acc1", 1_000L, 0)
        val key2 = DeduplicationKeys.forTweet("acc1", 1_000L, 1)
        assertNotEquals(key1, key2)
    }

    @Test
    fun `accountId 为空时抛出异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeduplicationKeys.forTweet("", 1_000L, 0)
        }
    }

    @Test
    fun `sequenceNo 为负数时抛出异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeduplicationKeys.forTweet("acc1", 1_000L, -1)
        }
    }

    @Test
    fun `windowStart 为 0 时仍可生成键`() {
        val key = DeduplicationKeys.forTweet("acc1", 0L, 0)
        assertEquals("acc1_0_0", key)
    }
}

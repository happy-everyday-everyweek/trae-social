package com.trae.social.core.data.util

import kotlinx.coroutines.CancellationException

/**
 * #185: 同 [runCatching]，但重抛 [CancellationException] 以保持协程结构化并发语义。
 *
 * Kotlin 标准库的 [runCatching] 会捕获所有 [Throwable]（包括 [CancellationException]），
 * 在 suspend 上下文中使用会破坏协程取消语义：viewModelScope 取消时正在执行的 suspend
 * 调用抛出 [CancellationException]，runCatching 把它当普通失败处理并继续执行回滚/状态
 * 更新逻辑，导致协程无法正确取消且状态被错误覆盖。
 *
 * 本函数在捕获后显式重抛 [CancellationException]，其余异常仍封装为 [Result.failure]。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

package com.yueyin.tv.core.data.api

import com.yueyin.tv.core.model.AppException

internal class ApiRequestFailure(
    val retryable: Boolean,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(cause)

internal val AppException.isRetryableRequestFailure: Boolean
    get() = (cause as? ApiRequestFailure)?.retryable == true

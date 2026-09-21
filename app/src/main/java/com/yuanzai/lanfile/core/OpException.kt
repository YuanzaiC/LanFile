package com.yuanzai.lanfile.core

/**
 * 带 HTTP 状态码的业务异常，服务端会把 [status] 直接映射为响应码。
 */
class OpException(val status: Int, message: String) : Exception(message) {

    companion object {
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val PAYLOAD_TOO_LARGE = 413
        const val INTERNAL = 500
    }
}
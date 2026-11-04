package com.example.tvdashboard.web

/** Lỗi khi tải trang chính của dashboard. */
data class LoadError(val kind: Kind, val detail: String? = null, val diagnosticCode: String? = null) {
    enum class Kind { NETWORK, HTTP_SERVER, SSL, TIMEOUT }
}

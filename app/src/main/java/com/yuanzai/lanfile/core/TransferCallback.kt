package com.yuanzai.lanfile.core

/** 传输/复制被用户取消。 */
class OperationCancelledException : Exception("操作已取消")

/** 批量操作的结果。 */
data class BatchResult(
    val succeeded: Int,
    val errors: List<String>
) {
    val failed: Int get() = errors.size
}

/** 传输进度回调（复制 / 移动 / 导入）。 */
interface TransferCallback {
    /**
     * @param name      当前处理的文件名
     * @param copied    已处理字节数（所有文件累计）
     * @param total     总字节数
     * @param fileIndex 当前第几个文件（从 1 开始）
     * @param fileCount 文件总数
     * @param currentFileBytes 当前文件已处理字节
     * @param currentFileSize  当前文件总字节
     */
    fun onProgress(
        name: String,
        copied: Long,
        total: Long,
        fileIndex: Int,
        fileCount: Int,
        currentFileBytes: Long,
        currentFileSize: Long
    )

    fun isCancelled(): Boolean = false
}
package com.dut.runmate.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * v1.6.1：非致命异常落盘（filesDir/crash_log.txt，与 App 全局崩溃日志同文件）。
 * 供各 Fragment / Service 在防御性 try/catch 里记录被吞掉的异常，
 * 用户可在 设置 → 查看崩溃日志 里复制反馈，远程定位不再靠猜。
 * 保留最近 64KB，避免无限增长。
 */
object SoftLog {

    fun write(dir: File, where: String, e: Throwable) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val f = File(dir, "crash_log.txt")
            val head = "\n[SOFT] time=${System.currentTimeMillis()} $where\n"
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + head + sw.toString()).takeLast(64 * 1024))
        } catch (_: Throwable) {
        }
    }

    fun write(dir: File, where: String, msg: String) {
        try {
            val f = File(dir, "crash_log.txt")
            val head = "\n[SOFT] time=${System.currentTimeMillis()} $where\n"
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + head + msg).takeLast(64 * 1024))
        } catch (_: Throwable) {
        }
    }
}

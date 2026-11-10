package com.example.packvideo

import android.content.Context
import android.provider.MediaStore
import androidx.work.Worker
import androidx.work.WorkerParameters

class CleanupWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        runCleanup(applicationContext)
        return Result.success()
    }
    companion object {
        fun runCleanup(ctx: Context) {
            try {
                val days = ctx.getSharedPreferences("s", 0).getInt("days", 28)
                val cutoff = System.currentTimeMillis() / 1000 - days * 86400L
                // Xoá vĩnh viễn (không qua thùng rác) các video do app tạo, cũ hơn X ngày
                ctx.contentResolver.delete(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DATE_ADDED} < ?",
                    arrayOf("Movies/DonHang%", cutoff.toString())
                )
            } catch (_: Exception) {}
        }
    }
}

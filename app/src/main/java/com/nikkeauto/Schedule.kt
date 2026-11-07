package com.nikkeauto

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.util.Calendar

/** 定時自動執行一鍵日常：用 AlarmManager 每天叫一次。 */
object Schedule {
    private const val REQ = 4040

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQ, Intent(ctx, ScheduleReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 依設定排下一次；關閉時取消。 */
    fun apply(ctx: Context) {
        val prefs = Prefs(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        am.cancel(pi)
        if (!prefs.scheduleOn) return
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.scheduleHour)
            set(Calendar.MINUTE, prefs.scheduleMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 30_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
    }

    fun nextText(prefs: Prefs) = "%02d:%02d".format(prefs.scheduleHour, prefs.scheduleMinute)
}

/** 鬧鐘到了：點亮螢幕並開始一鍵日常，再排明天的。 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Schedule.apply(ctx)   // 先排明天
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return
        val i = Intent(ctx, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            // 某些系統不允許背景開畫面，就直接開始（螢幕需亮著）
            AutoService.instance?.let { it.scheduledStart() }
        }
    }
}

/** 透明畫面：點亮螢幕、解除沒有密碼的鎖定畫面，然後開始一鍵日常。 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (Build.VERSION.SDK_INT >= 26) km.requestDismissKeyguard(this, null)
        Handler(Looper.getMainLooper()).postDelayed({
            // 無障礙服務沒開時無法執行，只能放棄這次
            AutoService.instance?.scheduledStart()
            finish()
        }, 1500)
    }
}

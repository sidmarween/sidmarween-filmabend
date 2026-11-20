package de.marween.filmabend

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Schaut alle ~15 Minuten nach, ob dein Partner dir etwas vorgeschlagen hat,
 * und zeigt dann eine Benachrichtigung. Kostenlos, ohne eigenen Server.
 */
class SuggestionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        val hid = applicationContext.getSharedPreferences("filmabend", Context.MODE_PRIVATE)
            .getString("hid", null) ?: return Result.success()
        return try {
            val col = FirebaseFirestore.getInstance().collection("households").document(hid).collection("suggestions")
            val snap = col.whereEqualTo("to", uid).whereEqualTo("notified", false).get(Source.SERVER).await()
            val docs = snap.documents
            if (docs.isNotEmpty()) {
                val hh = FirebaseFirestore.getInstance().collection("households").document(hid).get(Source.SERVER).await()
                val names = hh.get("names") as? Map<*, *>
                val sugg = docs.mapNotNull { Suggestion.from(it) }
                val fromName = sugg.firstOrNull()?.from?.let { names?.get(it)?.toString() } ?: "Dein Schatz"
                val text = if (sugg.size == 1) {
                    val s = sugg.first()
                    "„${s.title}“" + if (s.note.isNotBlank()) " – ${s.note}" else ""
                } else sugg.joinToString(", ") { it.title }
                notify(
                    applicationContext,
                    if (sugg.size == 1) "$fromName schlägt dir was vor 🍿" else "$fromName hat dir ${sugg.size} Vorschläge geschickt 🍿",
                    text
                )
                FirebaseFirestore.getInstance().runBatch { b ->
                    docs.forEach { b.update(it.reference, "notified", true) }
                }.await()
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val CHANNEL = "vorschlaege"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SuggestionWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("vorschlaege", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun notify(ctx: Context, title: String, text: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Vorschläge vom Partner", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).putExtra("tab", 3).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(4711, n)
        }
    }
}

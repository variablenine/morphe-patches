package app.variablenine.nudge;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.variablenine.nudge.core.Log;
import app.variablenine.nudge.core.NotificationParser;
import app.variablenine.nudge.core.NotificationParser.Reminder;
import app.variablenine.nudge.core.Prefs;
import app.variablenine.nudge.core.Task;
import app.variablenine.nudge.core.TaskBook;
import app.variablenine.nudge.core.TaskIds;
import app.variablenine.nudge.core.TaskStore;

/**
 * Receives the phone's notifications once the user has granted notification access,
 * and keeps the ones from the chosen apps as reminders.
 *
 * <p>Notifications from every other app are dropped on the first line of
 * {@link #handle}: nothing about them is read, stored or passed on.
 *
 * <p>Every entry point swallows its own failures. This code runs inside someone
 * else's app, and a reminder that fails to show must never become a crash there.
 */
public final class NudgeListenerService extends NotificationListenerService {

    /**
     * The same notification posted again within this window (apps do that when one is
     * tapped, expanded or updated) is the same request, not a new one.
     */
    private static final long REPOST_WINDOW = 10 * TaskBook.MINUTE;

    private static volatile NudgeListenerService connected;

    /** Notification key and reminder id to the time it was first seen. Small and in memory only. */
    private final Map<String, Long> firstSeen = new LinkedHashMap<String, Long>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 96;
        }
    };

    /** The running listener, or null while the system has it disconnected. */
    public static NudgeListenerService connected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        connected = this;
        try {
            Nudge.onListenerChanged(this);
            scanActive();
        } catch (Throwable t) {
            Log.w("listener connect failed", t);
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (connected == this) connected = null;
        try {
            Nudge.onListenerChanged(this);
        } catch (Throwable t) {
            Log.w("listener disconnect failed", t);
        }
    }

    @Override
    public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Throwable t) {
            Log.w("notification handling failed", t);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Deliberately nothing: a reminder outlives its notification. That is the point.
    }

    /** Picks up what is already in the notification shade, e.g. right after access was granted. */
    void scanActive() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (Throwable t) {
            // The system throws if the listener is not fully bound yet.
            return;
        }
        if (active == null) return;
        for (StatusBarNotification sbn : active) {
            try {
                handle(sbn);
            } catch (Throwable t) {
                Log.w("notification handling failed", t);
            }
        }
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;
        Prefs prefs = Prefs.get(this);
        if (!prefs.isSource(pkg)) return;

        List<Reminder> reminders = NotificationParser.parse(sbn.getNotification(), sbn.getPostTime());
        if (reminders.isEmpty()) return;
        TaskStore store = TaskStore.get(this);
        for (Reminder r : reminders) {
            prefs.noteSender(r.sender);
            if (prefs.isSenderMuted(r.sender)) continue;
            long time = steadyTime(sbn.getKey(), pkg, r);
            store.ingest(pkg, r.sender, r.text, time, TaskStore.FROM_NOTIFICATION);
        }
    }

    private long steadyTime(String key, String pkg, Reminder r) {
        String k = key + "|" + TaskIds.of(pkg, r.text);
        synchronized (firstSeen) {
            Long first = firstSeen.get(k);
            if (first != null && Math.abs(r.time - first) < REPOST_WINDOW) return first;
            firstSeen.put(k, r.time);
            return r.time;
        }
    }

    /**
     * Dismisses the notification a finished reminder came from, if it is still showing
     * and everything else in it is finished too.
     */
    void clearNotificationOf(String taskId) {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (Throwable t) {
            return;
        }
        if (active == null) return;
        Prefs prefs = Prefs.get(this);
        TaskStore store = TaskStore.get(this);
        for (StatusBarNotification sbn : active) {
            try {
                String pkg = sbn.getPackageName();
                if (pkg == null || !prefs.isSource(pkg)) continue;
                List<Reminder> reminders =
                        NotificationParser.parse(sbn.getNotification(), sbn.getPostTime());
                boolean holdsIt = false;
                boolean allFinished = !reminders.isEmpty();
                for (Reminder r : reminders) {
                    String id = TaskIds.of(pkg, r.text);
                    if (id.equals(taskId)) holdsIt = true;
                    Task t = store.get(id);
                    if (t == null || !t.isClosed()) allFinished = false;
                }
                if (holdsIt && allFinished) cancelNotification(sbn.getKey());
            } catch (Throwable t) {
                Log.w("clearing notification failed", t);
            }
        }
    }
}

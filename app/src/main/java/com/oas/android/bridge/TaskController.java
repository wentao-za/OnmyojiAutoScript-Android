// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from MaaMeow ActivityUtils: preserve existing game tasks when changing displays.
package com.oas.android.bridge;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class TaskController {
    private TaskController() { }

    @SuppressWarnings("deprecation")
    static ActivityManager.RunningTaskInfo find(String packageName) throws Exception {
        ActivityManager manager = (ActivityManager) new ShellContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100)) {
            if ((task.topActivity != null && packageName.equals(task.topActivity.getPackageName()))
                    || (task.baseActivity != null && packageName.equals(task.baseActivity.getPackageName()))) return task;
        }
        return null;
    }

    static int displayOf(ActivityManager.RunningTaskInfo task) throws Exception {
        if (task == null) return -2;
        if (Build.VERSION.SDK_INT < 29) return -1;
        Field field = task.getClass().getField("displayId");
        return field.getInt(task);
    }

    static int displayOf(String packageName) throws Exception { return displayOf(find(packageName)); }

    @SuppressWarnings("deprecation")
    static boolean isTopOnDisplay(String packageName, int display) throws Exception {
        ActivityManager manager = (ActivityManager) new ShellContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100))
            if (displayOf(task) == display) return task.topActivity != null && packageName.equals(task.topActivity.getPackageName());
        return false;
    }

    /**
     * Android 11 起用 moveRootTaskToDisplay(rootTaskId, displayId)，第一个参数即任务 id。
     * Android 10 只有 moveStackToDisplay(stackId, displayId)，第一个参数是 ActivityStack 的 id，
     * 传任务 id 会抛 IllegalArgumentException: moveStackToDisplay: Unknown stackId=NN
     * （实测该机 stackId=22 / taskId=50）。因此 Q 走 shell 的 am display move-stack。
     */
    static boolean moveExisting(String packageName, int target) throws Exception {
        ActivityManager.RunningTaskInfo task = find(packageName);
        if (task == null) return false;
        int current = displayOf(task);
        if (current < 0) throw new IllegalStateException("系统无法确认游戏所在屏幕，请先使用前台模式测试");
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "activity_task");
        Object manager = Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (current != target) migrate(manager, task, packageName, target);
        manager.getClass().getMethod("setFocusedTask", int.class).invoke(manager, task.id);
        SystemClock.sleep(600);
        long deadline = SystemClock.uptimeMillis() + 3000;
        do {
            if (displayOf(packageName) == target) return true;
            // Android 10 的 getRunningTasks 对虚拟屏任务的 displayId 可能滞后，用 am stack list 复核
            if (Build.VERSION.SDK_INT < 30 && displaysOf(packageName).contains(target)) return true;
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException("游戏未留在目标虚拟屏（可能在屏幕变化后退出），请导出诊断记录");
    }

    private static void migrate(Object manager, ActivityManager.RunningTaskInfo task, String packageName, int target) throws Exception {
        try {
            manager.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(manager, task.id, target);
            return;
        } catch (NoSuchMethodException noRootTaskApi) {
            // Android 10 没有 moveRootTaskToDisplay，落到下面的 stack 迁移
        }
        int stackId = stackOf(packageName, target);
        if (stackId < 0) throw new IllegalStateException("找不到游戏所属的 stack，无法迁移到虚拟屏");
        String output = shell("am", "display", "move-stack", Integer.toString(stackId), Integer.toString(target));
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("its current displayid")) return;
        if (lower.contains("exception") || lower.contains("error") || lower.contains("unknown"))
            throw new IllegalStateException(output.trim());
    }

    /** 取游戏任务所在 stack：优先返回还没在目标屏上的那个（可能有渠道 SDK 另起的重复任务）。 */
    private static int stackOf(String packageName, int target) {
        int fallback = -1;
        for (int[] stack : stacksOf(packageName)) {
            if (stack[1] != target) return stack[0];
            if (fallback < 0) fallback = stack[0];
        }
        return fallback;
    }

    private static List<Integer> displaysOf(String packageName) {
        List<Integer> displays = new ArrayList<>();
        for (int[] stack : stacksOf(packageName)) if (!displays.contains(stack[1])) displays.add(stack[1]);
        return displays;
    }

    /** 解析 `am stack list`，返回命中的 {stackId, displayId}。仅 Android 10 路径使用。 */
    private static List<int[]> stacksOf(String packageName) {
        List<int[]> matches = new ArrayList<>();
        int stackId = -1;
        int displayId = -1;
        try {
            for (String raw : shell("am", "stack", "list").split("\n")) {
                String line = raw.trim();
                if (line.startsWith("Stack id=") || line.startsWith("RootTask id=")) {
                    stackId = intField(line, "id=");
                    displayId = intField(line, "displayId=");
                    continue;
                }
                if (stackId < 0 || !line.startsWith("taskId=")) continue;
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String component = line.substring(colon + 1).trim();
                int slash = component.indexOf('/');
                if (slash > 0 && packageName.equals(component.substring(0, slash)))
                    matches.add(new int[]{stackId, displayId});
            }
        } catch (Exception error) {
            android.util.Log.w("OAS", "am stack list failed", error);
        }
        return matches;
    }

    private static int intField(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) return -1;
        int index = at + key.length();
        int value = 0;
        boolean any = false;
        while (index < line.length() && Character.isDigit(line.charAt(index))) {
            value = value * 10 + (line.charAt(index) - '0');
            index++;
            any = true;
        }
        return any ? value : -1;
    }

    private static String shell(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = stream.read(chunk)) != -1) buffer.write(chunk, 0, n);
            } catch (Exception ignored) { }
        });
        drain.start();
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IllegalStateException("设备命令超时: " + String.join(" ", command));
        }
        drain.join(1000);
        return buffer.toString("UTF-8");
    }
}


package com.baked.romcontrol.service;
import android.util.Log;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import com.baked.romcontrol.R;

import com.baked.romcontrol.performance.CPUSettings;
import com.baked.romcontrol.performance.OtherSettings;
import com.baked.romcontrol.performance.Voltage;
import com.baked.romcontrol.performance.VoltageControlSettings;
import com.baked.romcontrol.util.CMDProcessor;
import com.baked.romcontrol.util.Helpers;

public class BootService extends Service {

    public static boolean servicesStarted = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
        }
        new BootWorker(this).execute();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    class BootWorker extends AsyncTask<Void, Void, Void> {

        Context c;

        public BootWorker(Context c) {
            this.c = c;
        }

        @Override
        protected Void doInBackground(Void... args) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(c);
            final CMDProcessor cmd = new CMDProcessor();

            if (HeadphoneService.getUserHeadphoneAudioMode(c) != -1
                    || HeadphoneService.getUserBTAudioMode(c) != -1) {
                c.startService(new Intent(c, HeadphoneService.class));
            }

            if (FlipService.getUserFlipAudioMode(c) != -1
                    || FlipService.getUserCallSilent(c) != 0)
                c.startService(new Intent(c, FlipService.class));

            if (preferences.getBoolean("cpu_boot", false)) {
                final String max = preferences.getString(
                        "max_cpu", null);
                final String min = preferences.getString(
                        "min_cpu", null);
                final String gov = preferences.getString(
                        "gov", null);
                final String io = preferences.getString("io", null);
                
                boolean mIsTegra3 = c.getResources().getBoolean(
                                        com.android.internal.R.bool.config_isTegra3);
                int numOfCpu = 1;
                String numOfCpus = Helpers.readOneLine(CPUSettings.NUM_OF_CPUS);
                String[] cpuCount = numOfCpus.split("-");
    
                if (cpuCount.length > 1) {
                    try {
                        int cpuStart = Integer.parseInt(cpuCount[0]);
                        int cpuEnd = Integer.parseInt(cpuCount[1]);

                        numOfCpu = cpuEnd - cpuStart + 1;

                        if (numOfCpu < 0)
                            numOfCpu = 1;
                    } catch (NumberFormatException ex) {
                        numOfCpu = 1;
                    }
                }

                for (int i = 0; i < numOfCpu; i++) {
                    if (max != null) {
                        cmd.su.runWaitFor("busybox echo " + max +
                            " > " + CPUSettings.MAX_FREQ
                            .replace("cpu0", "cpu" + i));
                    }
                    
                    if (min != null) {
                        cmd.su.runWaitFor("busybox echo " + min +
                            " > " + CPUSettings.MIN_FREQ
                            .replace("cpu0", "cpu" + i));
                    }
                    
                    if (gov != null) {
                        cmd.su.runWaitFor("busybox echo " + gov +
                            " > " + CPUSettings.GOVERNOR.
                            replace("cpu0", "cpu" + i));
                    }
                }

                if (mIsTegra3 && max != null) {
                    cmd.su.runWaitFor("busybox echo " + max + 
                        " > " + CPUSettings.TEGRA_MAX_FREQ);
                }
                
                if (io != null) {
                    cmd.su.runWaitFor("busybox echo " + io +
                        " > " + CPUSettings.IO_SCHEDULER);
                }
            }

            if (preferences.getBoolean(VoltageControlSettings
                    .KEY_APPLY_BOOT, false)) {
                final List<Voltage> volts = VoltageControlSettings
                    .getVolts(preferences);
                final StringBuilder sb = new StringBuilder();
                for (final Voltage volt : volts) {
                    sb.append(volt.getSavedMV() + " ");
                }
                cmd.su.runWaitFor("busybox echo " + sb.toString() +
                        " > " + VoltageControlSettings.MV_TABLE0);
                if (new File(VoltageControlSettings.MV_TABLE1).exists()) {
                    cmd.su.runWaitFor("busybox echo " +
                            sb.toString() + " > " +
                            VoltageControlSettings.MV_TABLE1);
                }
                if (new File(VoltageControlSettings.MV_TABLE2).exists()) {
                    cmd.su.runWaitFor("busybox echo " +
                            sb.toString() + " > " +
                            VoltageControlSettings.MV_TABLE2);
                }
                if (new File(VoltageControlSettings.MV_TABLE3).exists()) {
                    cmd.su.runWaitFor("busybox echo " +
                            sb.toString() + " > " +
                            VoltageControlSettings.MV_TABLE3);
                }
            }

            boolean FChargeOn = preferences.getBoolean("fast_charge_boot", false);
            try {
                File fastcharge = new File("/sys/kernel/fast_charge",
                        "force_fast_charge");
                FileWriter fwriter = new FileWriter(fastcharge);
                BufferedWriter bwriter = new BufferedWriter(fwriter);
                bwriter.write(FChargeOn ? "1" : "0");
                bwriter.close();
                Intent i = new Intent();
                i.setAction("com.baked.romcontrol.FCHARGE_CHANGED");
                c.sendBroadcast(i);
            } catch (IOException e) {
            }

            if (FChargeOn) {
                // add notification to warn user they can only charge
                CharSequence contentTitle = c
                        .getText(R.string.fast_charge_notification_title);
                CharSequence contentText = c
                        .getText(R.string.fast_charge_notification_message);

                Notification n = new Notification.Builder(c)
                        .setAutoCancel(true)
                        .setContentTitle(contentTitle)
                        .setContentText(contentText)
                        .setSmallIcon(R.drawable.ic_rom_control_general)
                        .setWhen(System.currentTimeMillis())
                        .getNotification();

                NotificationManager nm = (NotificationManager) getApplicationContext()
                        .getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(1337, n);
            }

            if (preferences.getBoolean("free_memory_boot", false)) {
                final String values = preferences.getString(
                        "free_memory", null);
                if (!values.equals(null)) {
                    cmd.su.runWaitFor("busybox echo " + values +
                            " > /sys/module/lowmemorykiller/parameters/minfree");
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            servicesStarted = true;
            stopSelf();
        }

    }

}

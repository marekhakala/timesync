package me.tatarka.timesync.lib;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static me.tatarka.timesync.lib.MathUtil.randomInRange;

public class TimeSyncService extends IntentService {
    public static final String META_DATA_NAME = "me.tatarka.timesync.TimeSync";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static final String TYPE = "type";
    private static final int TYPE_START = 0;
    private static final int TYPE_STOP = 1;
    private static final int TYPE_SYNC = 2;
    private static final int TYPE_NETWORK_BACK = 3;
    private static final int TYPE_POWER_CHANGED = 4;

    private static final String NAME = "name";
    private static final String POWER_CONNECTED = "power_connected";

    private Preferences prefs;
    private long seed;
    private boolean powerConnected;

    private Map<String, TimeSyncListener> listeners = new HashMap<>();

    public TimeSyncService() {
        super(TimeSyncService.class.getName());
    }

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = new Preferences(this);
        seed = findOrCreateSeed(prefs);
        powerConnected = prefs.isPowerConnected();

        List<String> names = readXmlResource();
        for (String name : names) {
            try {
                listeners.put(name, (TimeSyncListener) Class.forName(name).newInstance());
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                throw new IllegalArgumentException("Listener " + name + " must be public and have an empty constructor");
            }
        }
    }

    private List<String> readXmlResource() {
        List<String> names = new ArrayList<>();

        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            int res = appInfo.metaData.getInt(META_DATA_NAME);
            if (res == 0) {
                throw new IllegalArgumentException("You must declare <meta-data android:name=\"" + META_DATA_NAME + "\" android:resource=\"@xml/[RESOURCE_NAME]\"/> in your AndroidManifest.xml");
            }
            XmlPullParser parser = getResources().getXml(res);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG) {
                    if (parser.getName().equals("listener")) {
                        String name = parser.getAttributeValue(ANDROID_NS, "name");
                        if (name == null) {
                            throw new IllegalArgumentException("Your <listener/> must have the attribute android:name=\"[CLASS_NAME]\"");
                        }
                        if (name.startsWith(".")) {
                            name = getPackageName() + name;
                        }
                        names.add(name);
                    }
                }
                parser.next();
            }
            return names;
        } catch (PackageManager.NameNotFoundException | IOException e) {
            throw new IllegalStateException(e);
        } catch (XmlPullParserException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void start(Context context) {
        context.startService(getStartIntent(context));
    }

    public static void stop(Context context) {
        context.startService(getStopIntent(context));
    }

    public static void sync(Context context, String name) {
        context.startService(getSyncIntent(context, name));
    }

    static void networkBack(Context context) {
        context.startService(getNetworkBackIntent(context));
    }

    static void powerChanged(Context context, boolean connected) {
        context.startService(getPowerChangedIntent(context, connected));
    }

    public static Intent getStartIntent(Context context) {
        Intent intent = new Intent(context, TimeSyncService.class);
        intent.putExtra(TYPE, TYPE_START);
        return intent;
    }

    public static Intent getStopIntent(Context context) {
        Intent intent = new Intent(context, TimeSyncService.class);
        intent.putExtra(TYPE, TYPE_STOP);
        return intent;
    }

    public static Intent getSyncIntent(Context context, String name) {
        Intent intent = new Intent(context, TimeSyncService.class);
        intent.setData(Uri.parse("timesync://" + name));
        intent.putExtra(TYPE, TYPE_SYNC);
        intent.putExtra(NAME, name);
        return intent;
    }

    static Intent getNetworkBackIntent(Context context) {
        Intent intent = new Intent(context, TimeSyncService.class);
        intent.putExtra(TYPE, TYPE_NETWORK_BACK);
        return intent;
    }

    static Intent getPowerChangedIntent(Context context, boolean connected) {
        Intent intent = new Intent(context, TimeSyncService.class);
        intent.putExtra(TYPE, TYPE_POWER_CHANGED);
        intent.putExtra(POWER_CONNECTED, connected);
        return intent;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        switch (intent.getIntExtra(TYPE, 0)) {
            case TYPE_START: {
                onHandleStart();
                break;
            }
            case TYPE_STOP: {
                onHandleStop();
                break;
            }
            case TYPE_SYNC: {
                String name = intent.getStringExtra(NAME);
                TimeSyncListener listener = listeners.get(name);
                if (listener != null) {
                    onHandleSync(listener);
                }
                break;
            }
            case TYPE_NETWORK_BACK: {
                for (TimeSyncListener listener : listeners.values()) {
                    onHandleAdd(listener);
                }
                break;
            }
            case TYPE_POWER_CHANGED: {
                boolean connected = intent.getBooleanExtra(POWER_CONNECTED, false);
                onHandlePowerChanged(connected);
                // TimeSyncPowerReceiver is a WakefulBroadcastReceiver, so make sure to release the lock.
                TimeSyncPowerReceiver.completeWakefulIntent(intent);
                break;
            }
        }
    }

    private void onHandleStart() {
        removeAll();
        for (TimeSyncListener listener : listeners.values()) {
            onHandleAdd(listener);
        }
        TimeSyncPowerReceiver.enable(this);
    }

    private void onHandleStop() {
        removeAll();
        TimeSyncNetworkReceiver.disable(this);
        TimeSyncPowerReceiver.disable(this);
    }

    private void onHandleAdd(TimeSyncListener listener) {
        long time = calculateTime(listener);

        if (time > 0) {
            int alarmType = powerConnected ? AlarmManager.RTC_WAKEUP : AlarmManager.RTC;
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = getSyncIntent(this, listener.getClass().getName());
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 0);
            alarmManager.cancel(pendingIntent);
            alarmManager.set(alarmType, time, pendingIntent);
        }
    }

    private void onHandleSync(TimeSyncListener listener) {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo != null && netInfo.isConnectedOrConnecting()) {
            try {
                listener.onSync(this);
                onHandleAdd(listener);
            } catch (Exception e) {
                onHandleFailureSyncError(listener);
            }
        } else {
            onHandleFailureNoNetwork(listener);
        }
    }

    private void onHandleFailureNoNetwork(TimeSyncListener listener) {
        removeAll();
        TimeSyncNetworkReceiver.enable(this);
    }

    private void removeAll() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        for (TimeSyncListener l : listeners.values()) {
            Intent intent = getSyncIntent(this, l.getClass().getName());
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 0);
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
    }

    private long calculateTime(TimeSyncListener listener) {
        TimeSyncConfig config = listener.getConfig();
        long exactTime = EventCalculator.getNextEvent(System.currentTimeMillis(), config.timeSpan);
        long rangeOffset = randomInRange(seed, -config.range / 2, config.range / 2);
        return exactTime + rangeOffset;
    }

    private void onHandleFailureSyncError(TimeSyncListener listener) {
        //TODO
    }

    private void onHandlePowerChanged(boolean connected) {
        powerConnected = connected;
        prefs.setPowerConnected(connected);
        // Remove and re-add alarms to take into account the state change.
        removeAll();
        for (TimeSyncListener listener : listeners.values()) {
            onHandleAdd(listener);
        }
    }

    private long findOrCreateSeed(Preferences prefs) {
        long seed = prefs.getSeed();
        if (seed != 0) return seed;

        String id = Build.SERIAL + Settings.Secure.ANDROID_ID;

        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (telephonyManager != null) {
            String deviceId = telephonyManager.getDeviceId();
            if (deviceId != null) id += deviceId;
        }

        // Use Random to evenly distribute values
        seed = new Random(id.hashCode()).nextLong();
        prefs.setSeed(seed);

        return seed;
    }

    private static class Preferences {
        private static final String NAME = "me.tatarka.timesync.SHARED_PREFS";
        private static final String SEED = "seed";
        private static final String POWER_CONNECTED = "power_connected";

        private SharedPreferences prefs;

        public Preferences(Context context) {
            prefs = context.getSharedPreferences(NAME, MODE_PRIVATE);
        }

        public long getSeed() {
            return prefs.getLong(SEED, 0);
        }

        public void setSeed(long seed) {
            prefs.edit().putLong(SEED, seed).commit();
        }

        public boolean isPowerConnected() {
            return prefs.getBoolean(POWER_CONNECTED, false);
        }

        public void setPowerConnected(boolean value) {
            prefs.edit().putBoolean(POWER_CONNECTED, value).commit();
        }
    }
}

package com.androidfakerun.companion;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.SystemClock;

public final class MockLocationReceiver extends BroadcastReceiver {
    private static final String PREFIX = "com.androidfakerun.companion.";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent.getAction();
            if ((PREFIX + "PING").equals(action)) {
                success("OK");
                return;
            }
            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if ((PREFIX + "RESET").equals(action)) {
                removeProvider(manager, LocationManager.GPS_PROVIDER);
                removeProvider(manager, LocationManager.NETWORK_PROVIDER);
                success("OK");
                return;
            }
            if (!(PREFIX + "SET_LOCATION").equals(action)) throw new IllegalArgumentException("未知操作");

            double latitude = Double.parseDouble(intent.getStringExtra("latitude"));
            double longitude = Double.parseDouble(intent.getStringExtra("longitude"));
            float speed = Float.parseFloat(intent.getStringExtra("speed"));
            setProvider(manager, LocationManager.GPS_PROVIDER, latitude, longitude, speed);
            // Some vendor ROMs do not allow the network provider to be replaced.
            // GPS is sufficient for fused-location clients; network is best effort.
            try { setProvider(manager, LocationManager.NETWORK_PROVIDER, latitude, longitude, speed); }
            catch (Exception ignored) { }
            success("OK");
        } catch (SecurityException exception) {
            failure("没有模拟位置权限，请在开发者选项中选择 Android Fake Run");
        } catch (Exception exception) {
            failure(exception.getMessage() == null ? "设置定位失败" : exception.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static void setProvider(LocationManager manager, String provider, double latitude, double longitude, float speed) {
        try { manager.removeTestProvider(provider); } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= 31) {
            ProviderProperties properties = new ProviderProperties.Builder()
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .build();
            manager.addTestProvider(provider, properties);
        } else {
            manager.addTestProvider(provider, false, false, false, false,
                    true, true, true, 1, 1);
        }
        manager.setTestProviderEnabled(provider, true);
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy(1.0f);
        location.setAltitude(0.0);
        location.setSpeed(speed);
        location.setBearing(0.0f);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= 18) location.setMock(true);
        manager.setTestProviderLocation(provider, location);
    }

    private static void removeProvider(LocationManager manager, String provider) {
        try { manager.setTestProviderEnabled(provider, false); } catch (Exception ignored) { }
        try { manager.removeTestProvider(provider); } catch (Exception ignored) { }
    }

    private void success(String message) {
        setResultCode(Activity.RESULT_OK);
        setResultData(message);
    }

    private void failure(String message) {
        setResultCode(Activity.RESULT_CANCELED);
        setResultData(message);
    }
}

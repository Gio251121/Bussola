package com.example.montagna_bussola;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;

import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.montagna_bussola.databinding.ActivityMainBinding;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements SensorEventListener{

    private final ActivityResultLauncher<String> activityResultLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    new ActivityResultCallback<Boolean>() {
                        @Override
                        public void onActivityResult(Boolean o) {
                            if (o) {
                                getLocation();
                            }
                        }
                    });

    private SensorManager sensorManager;
    Sensor accelerometer, magnetometer, pressureSensor;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private Location currentLocation;

    private float[] gravity;
    private float[] geomagnetic;

    private float currentDegree = 0f;
    private ActivityMainBinding binding;

    private ToneGenerator toneGenerator;
    private boolean searchingNorth = false;
    private long northHoldStart = 0;
    private boolean northAligned = false;
    private long lastBeepTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        EdgeToEdge.enable(this);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        if (pressureSensor == null) {
            binding.pressureTV.setText("Atmospheric Pressure: N/A");
        }

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(MainActivity.this);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            activityResultLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            getLocation();
        }

        binding.searchNorthButton.setOnClickListener(v -> {
            searchingNorth = !searchingNorth;
            if (searchingNorth) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
                binding.searchNorthButton.setText("Stop");
            } else {
                stopTone();
            }
        });
    }

    private void getLocation() {
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                TimeUnit.MINUTES.toMillis(5)
        )
                .setDurationMillis(TimeUnit.MINUTES.toMillis(5))
                .setWaitForAccurateLocation(false)
                .setMaxUpdates(1)
                .build();

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fusedLocationProviderClient.requestLocationUpdates(locationRequest, new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                super.onLocationResult(locationResult);

                for (Location location : locationResult.getLocations()) {
                    currentLocation = location;
                    binding.latitudeTV.setText("Lat: " + location.getLatitude());
                    binding.longitudeTV.setText("Lon: " + location.getLongitude());
                    getCityName(location);
                }
            }
        }, null);
    }

    @Override
    protected void onResume() {
        super.onResume();

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);

        // *** REGISTRA IL BAROMETRO SOLO SE ESISTE ***
        if (pressureSensor != null) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        sensorManager.unregisterListener(this, accelerometer);
        sensorManager.unregisterListener(this, magnetometer);

        if (pressureSensor != null) {
            sensorManager.unregisterListener(this, pressureSensor);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {

        if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            gravity = sensorEvent.values;
        }
        else if (sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic = sensorEvent.values;
            float magneticStrength = calculateMagneticStrength(geomagnetic);
            binding.magneticStrength.setText("Magnetic Strength: " + magneticStrength + " uT");
        }
        else if (sensorEvent.sensor.getType() == Sensor.TYPE_PRESSURE && pressureSensor != null) {
            float pressure = sensorEvent.values[0];
            binding.pressureTV.setText("Atmospheric Pressure: " + pressure + " hPa");
        }

        if (gravity != null && geomagnetic != null) {

            float[] R = new float[9];
            float[] I = new float[9];

            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {

                float[] orientation = new float[3];
                SensorManager.getOrientation(R, orientation);

                float azimuthInDegrees = (float) Math.toDegrees(orientation[0]);
                azimuthInDegrees = (azimuthInDegrees + 360) % 360;

                int degree = Math.round(azimuthInDegrees);

                float declination = 0;
                if (currentLocation != null) {
                    declination = getGeomagneticField(currentLocation).getDeclination();
                }

                int trueNorth = Math.round(degree + declination);

                RotateAnimation rotateAnimation = new RotateAnimation(
                        currentDegree, -degree,
                        Animation.RELATIVE_TO_SELF, 0.5f,
                        Animation.RELATIVE_TO_SELF, 0.5f);
                rotateAnimation.setDuration(210);
                rotateAnimation.setFillAfter(true);
                binding.compassImageView.startAnimation(rotateAnimation);

                currentDegree = -degree;

                binding.headingTV.setText(degree + "°");
                binding.trueHeadingTV.setText("True HDG: " + trueNorth + "°");
                binding.directionTV.setText(getDirection(degree));

                if (searchingNorth) {

                    float distanceToNorth = Math.abs(degree);
                    if (distanceToNorth > 180) distanceToNorth = 360 - distanceToNorth;

                    long now = System.currentTimeMillis();

                    if (distanceToNorth < 10) {
                        if (!northAligned) {
                            northAligned = true;
                            northHoldStart = now;
                        }

                        if (now - northHoldStart >= 3000) {
                            stopTone();
                            return;
                        }
                    } else {
                        northAligned = false;
                    }

                    long minDelay = 120;
                    long maxDelay = 1200;

                    long beepDelay =
                            (long) (minDelay + (distanceToNorth / 180f) * (maxDelay - minDelay));

                    if (now - lastBeepTime >= beepDelay) {
                        playBeep();
                        lastBeepTime = now;
                    }
                }
            }
        }
    }

    private String getDirection(float degree){
        if(degree>= 22.5 && degree < 67.5) return "NE";
        else if(degree>= 67.5 && degree < 112.5) return "E";
        else if(degree>= 112.5 && degree < 157.5) return "ES";
        else if(degree>= 157.5 && degree < 202.5) return "S";
        else if(degree>= 202.5 && degree < 247.5) return "SW";
        else if(degree>= 247.5 && degree < 292.5) return "W";
        else if(degree>= 292.5 && degree < 337.5) return "NW";
        else return "N";
    }

    private GeomagneticField getGeomagneticField(Location location){
        return new GeomagneticField(
                (float) location.getLatitude(),
                (float) location.getLongitude(),
                (float) location.getAltitude(),
                System.currentTimeMillis());
    }

    private float calculateMagneticStrength(float[] geomagnetic){
        return (float) Math.sqrt(
                geomagnetic[0] * geomagnetic[0] +
                        geomagnetic[1] * geomagnetic[1] +
                        geomagnetic[2] * geomagnetic[2]);
    }

    private void getCityName(Location location){
        Geocoder geocoder = new Geocoder(this, Locale.getDefault());
        try{
            List<Address> addresses = geocoder.getFromLocation(
                    location.getLatitude(),
                    location.getLongitude(),
                    1);

            if (addresses != null && !addresses.isEmpty()){
                binding.cityTV.setText(addresses.get(0).getLocality());
            } else {
                binding.cityTV.setText("Nessuna Città trovata");
            }
        } catch (IOException e) {
            binding.cityTV.setText("Nessuna Città trovata");
            e.printStackTrace();
        }
    }

    private void stopTone() {
        searchingNorth = false;
        northAligned = false;
        lastBeepTime = 0;

        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }

        binding.searchNorthButton.setText("Cerca Nord");
    }

    private void playBeep() {
        if (toneGenerator != null)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 80);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}

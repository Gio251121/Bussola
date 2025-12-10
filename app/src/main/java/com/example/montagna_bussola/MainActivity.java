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

    // Launcher per gestire la richiesta di permessi di localizzazione
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

    // Client per ottenere la posizione GPS
    private FusedLocationProviderClient fusedLocationProviderClient;
    private Location currentLocation;

    // Array per memorizzare i valori dei sensori
    private float[] gravity;
    private float[] geomagnetic;

    // Variabile per tenere traccia della rotazione corrente della bussola
    private float currentDegree = 0f;

    // View binding per accedere agli elementi dell'interfaccia
    private ActivityMainBinding binding;

    // Generatore di toni per la funzione "Cerca Nord"
    private ToneGenerator toneGenerator;

    // Variabili per la funzionalità "Cerca Nord"
    private boolean searchingNorth = false;
    private long northHoldStart = 0;
    private boolean northAligned = false;
    private long lastBeepTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Inizializza il view binding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Abilita il layout edge-to-edge (schermo intero)
        EdgeToEdge.enable(this);

        // Gestisce gli insets di sistema (barre di stato e navigazione)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Inizializza il SensorManager e i vari sensori
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);


        if (pressureSensor == null) {
            binding.pressureTV.setText("Atmospheric Pressure: N/A");
        }

        // Inizializza il client per la localizzazione GPS
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(MainActivity.this);

        // Controlla se il permesso di localizzazione è già concesso
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            activityResultLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            getLocation();
        }

        // Bottone "Cerca Nord"
        binding.searchNorthButton.setOnClickListener(v -> {
            searchingNorth = !searchingNorth; // Toggle della funzione
            if (searchingNorth) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
                binding.searchNorthButton.setText("Stop");
            } else {
                stopTone();
            }
        });
    }

    // Metodo per ottenere la posizione GPS dell'utente
    private void getLocation() {
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                TimeUnit.MINUTES.toMillis(5)
        )
                .setDurationMillis(TimeUnit.MINUTES.toMillis(5))
                .setWaitForAccurateLocation(false)
                .setMaxUpdates(1)
                .build();

        // Controlla nuovamente i permessi
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // Richiede gli aggiornamenti della posizione
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                super.onLocationResult(locationResult);

                // Per ogni posizione ricevuta
                for (Location location : locationResult.getLocations()) {
                    currentLocation = location;
                    // Aggiorna le TextView con latitudine e longitudine
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

        // Registra i listener per accelerometro e magnetometro
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);

        // Registra il listener per il barometro solo se il sensore esiste
        if (pressureSensor != null) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Deregistra tutti i listener
        sensorManager.unregisterListener(this, accelerometer);
        sensorManager.unregisterListener(this, magnetometer);

        if (pressureSensor != null) {
            sensorManager.unregisterListener(this, pressureSensor);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {

        // Gestisce i cambiamenti dall'accelerometro
        if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            gravity = sensorEvent.values;
        }
        // Gestisce i cambiamenti dal magnetometro
        else if (sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic = sensorEvent.values;
            // Calcola e mostra l'intensità del campo magnetico
            float magneticStrength = calculateMagneticStrength(geomagnetic);
            binding.magneticStrength.setText("Magnetic Strength: " + magneticStrength + " uT");
        }
        // Gestisce i cambiamenti dal barometro
        else if (sensorEvent.sensor.getType() == Sensor.TYPE_PRESSURE && pressureSensor != null) {
            float pressure = sensorEvent.values[0];
            binding.pressureTV.setText("Atmospheric Pressure: " + pressure + " hPa");
        }

        // Se abbiamo dati da entrambi accelerometro e magnetometro
        if (gravity != null && geomagnetic != null) {

            // Matrici per calcolare l'orientamento
            float[] R = new float[9];  // Matrice di rotazione
            float[] I = new float[9];  // Matrice di inclinazione

            // Calcola la matrice di rotazione dal campo gravitazionale e magnetico
            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {

                // Array per memorizzare l'orientamento (azimuth, pitch, roll)
                float[] orientation = new float[3];
                SensorManager.getOrientation(R, orientation);

                // Converte l'azimuth da radianti a gradi
                float azimuthInDegrees = (float) Math.toDegrees(orientation[0]);
                azimuthInDegrees = (azimuthInDegrees + 360) % 360; // Normalizza tra 0-360

                int degree = Math.round(azimuthInDegrees);

                // Calcola la declinazione magnetica (differenza tra nord magnetico e geografico)
                float declination = 0;
                if (currentLocation != null) {
                    declination = getGeomagneticField(currentLocation).getDeclination();
                }

                // Calcola il vero nord geografico
                int trueNorth = Math.round(degree + declination);

                // Crea l'animazione di rotazione per l'immagine della bussola
                RotateAnimation rotateAnimation = new RotateAnimation(
                        currentDegree, -degree,
                        Animation.RELATIVE_TO_SELF, 0.5f,
                        Animation.RELATIVE_TO_SELF, 0.5f);
                rotateAnimation.setDuration(210);
                rotateAnimation.setFillAfter(true);
                binding.compassImageView.startAnimation(rotateAnimation);

                // Aggiorna il grado corrente
                currentDegree = -degree;

                // Aggiorna le TextView con i valori calcolati
                binding.headingTV.setText(degree + "°");
                binding.trueHeadingTV.setText("True HDG: " + trueNorth + "°");
                binding.directionTV.setText(getDirection(degree));

                // Se la funzione "Cerca Nord" è attiva
                if (searchingNorth) {

                    // Calcola la distanza angolare dal nord
                    float distanceToNorth = Math.abs(degree);
                    if (distanceToNorth > 180) distanceToNorth = 360 - distanceToNorth;

                    long now = System.currentTimeMillis();

                    // Se siamo entro 10 gradi dal nord
                    if (distanceToNorth < 10) {
                        if (!northAligned) {
                            northAligned = true;
                            northHoldStart = now;
                        }

                        // Se siamo allineati al nord per 3 secondi, ferma i beep
                        if (now - northHoldStart >= 3000) {
                            stopTone();
                            return;
                        }
                    } else {
                        northAligned = false;
                    }

                    // Calcola la frequenza dei beep in base alla distanza dal nord
                    long minDelay = 120;   // Beep veloce quando vicini al nord
                    long maxDelay = 1200;  // Beep lento quando lontani dal nord

                    long beepDelay =
                            (long) (minDelay + (distanceToNorth / 180f) * (maxDelay - minDelay));

                    // Emette un beep se è passato abbastanza tempo dall'ultimo
                    if (now - lastBeepTime >= beepDelay) {
                        playBeep();
                        lastBeepTime = now;
                    }
                }
            }
        }
    }

    // Converte i gradi in direzione cardinale
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

    // Ottiene il campo geomagnetico per la posizione corrente
    private GeomagneticField getGeomagneticField(Location location){
        return new GeomagneticField(
                (float) location.getLatitude(),
                (float) location.getLongitude(),
                (float) location.getAltitude(),
                System.currentTimeMillis());
    }

    // Calcola l'intensità del campo magnetico usando il teorema di Pitagora 3D
    private float calculateMagneticStrength(float[] geomagnetic){
        return (float) Math.sqrt(
                geomagnetic[0] * geomagnetic[0] +
                        geomagnetic[1] * geomagnetic[1] +
                        geomagnetic[2] * geomagnetic[2]);
    }

    // Ottiene il nome della città dalla posizione GPS usando il Geocoder
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

    // Ferma la funzione "Cerca Nord" e rilascia le risorse
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

    // Emette un beep di 80ms
    private void playBeep() {
        if (toneGenerator != null)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 80);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
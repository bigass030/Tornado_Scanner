package com.example.behrouz_test;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HTTP_CALL";
    private EditText nameField, lagerField, bestandField, meField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText inputField = findViewById(R.id.inputField);
        nameField = findViewById(R.id.nameField);
        lagerField = findViewById(R.id.lagerField);
        bestandField = findViewById(R.id.bestandField);
        meField = findViewById(R.id.meField);


        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                String inputText = inputField.getText().toString();
                new Thread(() -> makeHttpRequest(inputText, nameField)).start();
                return true;
            }
            return false;
        });

        inputField.requestFocus();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        Button clearButton = findViewById(R.id.clearButton);
        clearButton.setOnClickListener(v -> {
            inputField.setText("");
            nameField.setText("");
            lagerField.setText("");
            bestandField.setText("");
            meField.setText("");
        });

        /*
        Button umbuchenButton = findViewById(R.id.umbuchenButton);
        umbuchenButton.setOnClickListener(v -> {
            new Thread(() -> sendUmbuchenRequest()).start();
        });
         */

    }

    private void makeHttpRequest(String numberToSend, EditText outputField) {
        try {
            String targetUrl = "http://10.0.20.26:8080/get_art";
            Log.i(TAG, "Trying to connect to " + targetUrl);

            URL url = new URL(targetUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            try (OutputStream os = connection.getOutputStream()) {
                os.write(numberToSend.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            //Log.i(TAG, "Response code: " + responseCode);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream())
            );
            String inputLine;
            StringBuilder response = new StringBuilder();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            String responseBody = response.toString();
            //Log.i(TAG, "Response body: " + responseBody);
            String[] parts = responseBody.split("\\|");
            String name = parts[0];
            String lager  = parts[1];
            String bestand = "" + (Double.parseDouble(parts[2]) + Double.parseDouble(parts[3]) + Double.parseDouble(parts[4]));
            String me = parts[5];

            runOnUiThread(() -> {
                //Toast.makeText(this, "Success: " + responseCode, Toast.LENGTH_LONG).show();
                outputField.setText(name); // update second field with response
                lagerField.setText(lager);
                bestandField.setText(bestand);
                meField.setText(me);
            });

        } catch (Exception e) {
            Log.e(TAG, "Exception occurred", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "Ungüeltig: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        }
    }

    /*
    private void sendUmbuchenRequest() {
        try {
            String targetUrl = "http://10.0.20.26:8080/umbuchen";
            Log.i(TAG, "Connecting to " + targetUrl);

            URL url = new URL(targetUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            // Optionally send data
            try (OutputStream os = connection.getOutputStream()) {
                os.write("umbuchen".getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            Log.i(TAG, "Umbuchen response code: " + responseCode);

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            runOnUiThread(() -> {
                Toast.makeText(this, "Umbuchen erfolgreich: " + responseCode, Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            Log.e(TAG, "Umbuchen failed", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "Fehler beim Umbuchen: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        }
    }
     */
}

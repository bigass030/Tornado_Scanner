package com.example.behrouz_test;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HTTP_CALL";

    private EditText inputField, nameField, bestandField, meField, mengeField;
    private Spinner lagerSpinner;

    private final List<String> currentLagerOptions = new ArrayList<>();
    private ArrayAdapter<String> lagerAdapter;

    // Prevent “auto-selected first item” from triggering jump immediately after loading data
    private boolean suppressNextJump = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputField = findViewById(R.id.inputField);
        nameField = findViewById(R.id.nameField);
        meField = findViewById(R.id.meField);
        lagerSpinner = findViewById(R.id.lagerSpinner);
        bestandField = findViewById(R.id.bestandField);
        mengeField = findViewById(R.id.menge);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        lagerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, currentLagerOptions);
        lagerSpinner.setAdapter(lagerAdapter);

        lagerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (suppressNextJump) return;

                // User selected "von" -> jump to "nach" and open keyboard
                bestandField.requestFocus();
                showKeyboard(bestandField);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        bestandField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                mengeField.requestFocus();
                showKeyboard(mengeField);
                return true;
            }
            return false;
        });

        mengeField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showKeyboard(mengeField);
        });

        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                String inputText = inputField.getText().toString().trim();
                if (inputText.isEmpty()) {
                    Toast.makeText(this, "Bitte Artikel-Nr scannen", Toast.LENGTH_SHORT).show();
                    return true;
                }
                new Thread(() -> makeHttpRequest(inputText)).start();
                return true;
            }
            return false;
        });

        inputField.requestFocus();

        Button clearButton = findViewById(R.id.clearButton);
        clearButton.setOnClickListener(v -> clearAllAndFocus());
    }

    private void clearAllAndFocus() {
        inputField.setText("");
        nameField.setText("");
        meField.setText("");
        bestandField.setText("");
        mengeField.setText("");

        suppressNextJump = true;
        currentLagerOptions.clear();
        lagerAdapter.notifyDataSetChanged();
        suppressNextJump = false;

        inputField.requestFocus();
    }

    private void showKeyboard(EditText field) {
        field.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void openSpinnerAutomatically() {
        // Open spinner dropdown reliably after UI updates
        lagerSpinner.post(() -> lagerSpinner.performClick());
    }

    private void makeHttpRequest(String textToSend) {
        HttpURLConnection connection = null;

        try {
            String encoded = URLEncoder.encode(textToSend, StandardCharsets.UTF_8.name());
            String targetUrl = "http://10.0.20.26:8080/artikel?text=" + encoded;

            Log.i(TAG, "Connecting to: " + targetUrl);

            URL url = new URL(targetUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);

            int responseCode = connection.getResponseCode();

            InputStream stream = (responseCode >= 400)
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            String responseBody = readAll(stream);
            Log.i(TAG, "HTTP " + responseCode + " body: " + responseBody);

            if (responseCode >= 400) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Server-Fehler HTTP " + responseCode, Toast.LENGTH_LONG).show()
                );
                return;
            }

            JSONObject obj = new JSONObject(responseBody);

            String benennung = obj.optString("benennung", "");
            String me = obj.optString("me", "");

            List<String> newOptions = new ArrayList<>();
            JSONArray lagers = obj.optJSONArray("lagernr");
            if (lagers != null) {
                for (int i = 0; i < lagers.length(); i++) {
                    String val = lagers.optString(i, "").trim();
                    if (!val.isEmpty()) newOptions.add(val);
                }
            }

            runOnUiThread(() -> {
                nameField.setText(benennung);
                meField.setText(me);

                bestandField.setText("");
                mengeField.setText("");

                // Update spinner list without immediately triggering the jump
                suppressNextJump = true;
                currentLagerOptions.clear();
                currentLagerOptions.addAll(newOptions);
                lagerAdapter.notifyDataSetChanged();

                // Reset selection
                if (!currentLagerOptions.isEmpty()) {
                    lagerSpinner.setSelection(0, false);
                }
                suppressNextJump = false;

                // Open dropdown automatically (no extra tap)
                if (currentLagerOptions.isEmpty()) {
                    Toast.makeText(this, "Keine Lagerplätze verfügbar", Toast.LENGTH_SHORT).show();
                } else {
                    openSpinnerAutomatically();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "HTTP/JSON Exception", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "Ungültig: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) sb.append(line);
        in.close();
        return sb.toString();
    }
}

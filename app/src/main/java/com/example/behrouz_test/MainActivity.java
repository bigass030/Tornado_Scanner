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
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HTTP_CALL";

    private EditText inputField, nameField, bestandField, meField, mengeField;
    private Spinner lagerSpinner;

    private final List<LagerItem> currentLagerItems = new ArrayList<>();
    private ArrayAdapter<LagerItem> lagerAdapter;

    // Prevent “auto-selected first item” from triggering jump immediately after loading data
    private boolean suppressNextJump = false;

    // For pretty numbers in spinner label
    private final DecimalFormat df = new DecimalFormat("0.##");

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

        lagerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, currentLagerItems);
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
                new Thread(() -> makeArtikelRequest(inputText)).start();
                return true;
            }
            return false;
        });

        // Undo/reset button inside Artikel-Nr field (your existing behavior)
        ImageButton clearIconButton = findViewById(R.id.clearIconButton);
        clearIconButton.setOnClickListener(v -> clearAllAndFocus());

        // Play/send button inside Menge field -> POST umbuchung
        ImageButton playButton = findViewById(R.id.playButton);
        playButton.setOnClickListener(v -> new Thread(this::sendUmbuchungPost).start());

        inputField.requestFocus();
    }

    private void clearAllAndFocus() {
        inputField.setText("");
        nameField.setText("");
        meField.setText("");
        bestandField.setText("");
        mengeField.setText("");

        suppressNextJump = true;
        currentLagerItems.clear();
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
        lagerSpinner.post(() -> lagerSpinner.performClick());
    }

    // ----------- 1) GET /artikel -----------
    private void makeArtikelRequest(String textToSend) {
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

            // parse lagerBestand array
            List<LagerItem> newItems = new ArrayList<>();
            JSONArray lb = obj.optJSONArray("lagerBestand");
            if (lb != null) {
                for (int i = 0; i < lb.length(); i++) {
                    JSONObject entry = lb.optJSONObject(i);
                    if (entry == null) continue;

                    String lagernr = entry.optString("lagernr", "").trim();
                    double bestand = entry.optDouble("bestand", 0.0);

                    if (!lagernr.isEmpty()) newItems.add(new LagerItem(lagernr, bestand));
                }
            }

            runOnUiThread(() -> {
                nameField.setText(benennung);
                meField.setText(me);

                bestandField.setText("");
                mengeField.setText("");

                // update spinner list without triggering jump immediately
                suppressNextJump = true;
                currentLagerItems.clear();
                currentLagerItems.addAll(newItems);
                lagerAdapter.notifyDataSetChanged();

                if (!currentLagerItems.isEmpty()) {
                    lagerSpinner.setSelection(0, false);
                }
                suppressNextJump = false;

                // 0 -> toast, 1 -> auto-select + go nach, 2+ -> open dropdown
                if (currentLagerItems.isEmpty()) {
                    Toast.makeText(this, "Keine Lagerplätze verfügbar", Toast.LENGTH_SHORT).show();
                } else if (currentLagerItems.size() == 1) {
                    lagerSpinner.setSelection(0, false);
                    bestandField.requestFocus();
                    showKeyboard(bestandField);
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

    // ----------- 2) POST /umbuchung -----------
    private void sendUmbuchungPost() {
        HttpURLConnection connection = null;

        try {
            String artnr = inputField.getText().toString().trim();
            String nach = bestandField.getText().toString().trim();

            LagerItem selected = (LagerItem) lagerSpinner.getSelectedItem();
            String von = (selected != null) ? selected.lagernr.trim() : "";

            String mengeRaw = mengeField.getText().toString().trim();
            // allow comma decimal input from DE keyboards
            mengeRaw = mengeRaw.replace(',', '.');

            if (artnr.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "Artikel-Nr fehlt", Toast.LENGTH_SHORT).show());
                return;
            }
            if (von.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "\"von\" fehlt", Toast.LENGTH_SHORT).show());
                return;
            }
            if (nach.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "\"nach\" fehlt", Toast.LENGTH_SHORT).show());
                return;
            }
            if (mengeRaw.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "Menge fehlt", Toast.LENGTH_SHORT).show());
                return;
            }

            double menge;
            try {
                menge = Double.parseDouble(mengeRaw);
            } catch (NumberFormatException nfe) {
                runOnUiThread(() -> Toast.makeText(this, "Menge ist ungültig", Toast.LENGTH_SHORT).show());
                return;
            }

            String targetUrl = "http://10.0.20.26:8080/umbuchung";
            Log.i(TAG, "POST to: " + targetUrl);

            // Build JSON body
            JSONObject body = new JSONObject();
            body.put("artnr", artnr);
            body.put("von", von);
            body.put("nach", nach);
            body.put("menge", menge);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(targetUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");

            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload);
            }

            int responseCode = connection.getResponseCode();
            InputStream stream = (responseCode >= 400)
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            String responseBody = readAll(stream);
            Log.i(TAG, "Umbuchung HTTP " + responseCode + " body: " + responseBody);

            runOnUiThread(() -> {
                if (responseCode >= 400) {
                    Toast.makeText(this, "Umbuchung Fehler HTTP " + responseCode, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Umbuchung OK", Toast.LENGTH_SHORT).show();
                    // Optional: clear only user-input fields after success
                    // bestandField.setText("");
                    // mengeField.setText("");
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Umbuchung Exception", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "Umbuchung Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show()
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

    private class LagerItem {
        final String lagernr;
        final double bestand;

        LagerItem(String lagernr, double bestand) {
            this.lagernr = lagernr;
            this.bestand = bestand;
        }

        @Override
        public String toString() {
            // Shown in spinner (you already aligned it earlier with monospace formatting in adapter version;
            // keeping a readable fallback here)
            return String.format("%-12s    %s", lagernr.trim(), df.format(bestand));
        }
    }
}
